"""Tunes the built-in AI's parameters (src/main/java/com/racingsim/ai/AiParams.java) with batches of
headless races and a separable CMA-ES (standard library only: no numpy / cma needed).

    ./gradlew evalArgs                      # once: compiles the headless race, writes build/eval.args
    python tools/train/tune_ai.py --track road --generations 25 --workers 10
    python tools/train/tune_ai.py --track road --evaluate src/main/resources/AI/road.properties --seeds 100-109

Each candidate is raced on the same few seeds (driver mixes) in a full field (14 cars, 2 laps, a
standing start from the grid); its cost is the field's average best lap plus penalties for contacts,
recoveries (crashes), abandoned passes and emergency swerves, less a reward for every completed pass.
The run's log and the best parameters so far go to tools/train/runs/<track>-<time>/.
"""
import argparse
import concurrent.futures as cf
import json
import math
import os
import random
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# name: (default, low, high). Defaults are the values in the code (AIDriver / SpeedProfile / VehicleSpec).
PARAMS = {
    "road": {
        "aiGrip": (1.50, 1.35, 1.75),        # the lowest grip a driver plans its corner speeds with
        "aiGripSpread": (0.10, 0.03, 0.16),  # range above it across the field
        "aiBrakeGrip": (0.82, 0.68, 0.96),   # share of the grip the braking points plan on
        "aiLateBrake": (0.95, 0.84, 1.05),   # ... braking late down the inside to pass
        "aiOutbrake": (8.0, 3.0, 14.0),      # how far back a late-braking pass may start (m, + 6 x aggression)
        "aiSling": (2.5, 1.0, 5.0),          # closing speed needed to pull out of the tow (m/s)
        "aiRoomClose": (3.0, 1.5, 5.0),      # closing speed assumed when judging if a pass fits (m/s)
        "aiPullOut": (12.0, 6.0, 20.0),      # pull-out distance behind the car being passed (m)
        "aiRunGap": (7.0, 4.0, 11.0),        # following distance on a draft run (m)
        "aiRunPull": (1.5, 0.0, 4.0),        # pull out within run gap + this, any closing speed (m)
        "aiStallGain": (0.0, -1.0, 1.0),     # give a pass up when gaining less than this (m/s)
        "aiDirtyAir": (0.08, 0.0, 0.16),     # corner speed lost per unit of draft (dirty air)
        "aiLookBase": (8.0, 4.0, 14.0),      # steering look-ahead (m) ...
        "aiLookK": (0.40, 0.20, 0.70),       # ... + per m/s
        "aiLatGain": (0.0025, 0.0008, 0.006),
        "aiLatDamp": (0.006, 0.002, 0.012),
    },
}
PARAMS["oval"] = {k: v for k, v in PARAMS["road"].items() if k not in ("aiLateBrake", "aiOutbrake", "aiBrakeGrip")}
PARAMS["oval"]["aiGrip"] = (1.22, 1.10, 1.40)

WEIGHTS = dict(contacts=4.0, recoveries=15.0, passesAborted=0.6, passesDone=-1.0, emergencyMoves=2.0, unfinished=30.0)


ARGS_FILE = os.path.join(ROOT, "build", "eval.args")


def snapshot_classes(run):
    """Copies the compiled game into the run, so rebuilding while it trains changes nothing."""
    import shutil
    with open(ARGS_FILE) as f:
        args = f.read()
    build = os.path.join(ROOT, "build").replace("\\", "/")
    for sub in ("classes/java/main", "classes/java/test", "resources/main", "resources/test"):
        src = os.path.join(ROOT, "build", *sub.split("/"))
        dst = os.path.join(run, "snapshot", *sub.split("/"))
        if os.path.isdir(src):
            shutil.copytree(src, dst)
        args = args.replace(build + "/" + sub, dst.replace("\\", "/"))
    out = os.path.join(run, "eval.args")
    with open(out, "w") as f:
        f.write(args)
    return out


def race(track, params, seed, cars, laps, tag, workdir):
    """One headless race; returns its results dict (None if it failed)."""
    prop = os.path.join(workdir, f"{tag}.properties")
    res = os.path.join(workdir, f"{tag}.json")
    with open(prop, "w") as f:
        for k, v in params.items():
            f.write(f"{k}={v:.6g}\n")
    cmd = ["java", "@" + ARGS_FILE, f"-Dtrack={track}", f"-DaiParams={prop}",
           f"-Dseed={seed}", f"-Dresults={res}", "com.racingsim.HeadlessSimCheck",
           str(140 * laps + 120), "-", str(cars), "ai", str(laps), "0", "0.8" if track == "road" else "0"]
    for attempt in range(3):   # (a JVM now and then fails to start when many start at once)
        try:
            log = os.path.join(workdir, f"{tag}.log")
            with open(log, "w") as lf:
                subprocess.run(cmd, cwd=ROOT, stdout=lf, stderr=subprocess.STDOUT, timeout=900, check=True)
            with open(res) as f:
                r = json.load(f)
            os.remove(log)
            return r
        except Exception as e:  # noqa: BLE001 - a race that keeps failing just scores badly
            print(f"race failed (attempt {attempt + 1}): {tag}: {e}", file=sys.stderr, flush=True)
            time.sleep(2 + 3 * attempt)
    return None


def cost(results, laps):
    """Lower is better. Averaged over the seeds."""
    total = 0.0
    for r in results:
        if r is None or r.get("avgBestLap") is None:
            total += 1000.0
            continue
        unfinished = sum(1 for c in r["order"] if not c["finished"])
        c = r["avgBestLap"]
        for k, w in WEIGHTS.items():
            c += w * (unfinished if k == "unfinished" else r[k])
        total += c
    return total / len(results)


def summary(results):
    keys = ["avgBestLap", "fastestLap", "contacts", "recoveries", "passStarts", "passesDone", "passesAborted",
            "outbrakeStarts", "outbrakesDone", "emergencyMoves"]
    ok = [r for r in results if r]
    return {k: round(sum((r[k] or 0) for r in ok) / max(len(ok), 1), 3) for k in keys}


class SepCMA:
    """Separable (diagonal) CMA-ES on [0, 1]^n (Ros & Hansen 2008)."""

    def __init__(self, x0, sigma, lam, rng):
        n = self.n = len(x0)
        self.m, self.sigma, self.lam, self.rng = list(x0), sigma, lam, rng
        self.mu = lam // 2
        w = [math.log(self.mu + 0.5) - math.log(i + 1) for i in range(self.mu)]
        s = sum(w)
        self.w = [x / s for x in w]
        self.mueff = 1.0 / sum(x * x for x in self.w)
        self.cs = (self.mueff + 2) / (n + self.mueff + 5)
        self.ds = 1 + 2 * max(0.0, math.sqrt((self.mueff - 1) / (n + 1)) - 1) + self.cs
        self.cc = (4 + self.mueff / n) / (n + 4 + 2 * self.mueff / n)
        c1 = 2 / ((n + 1.3) ** 2 + self.mueff)
        cmu = min(1 - c1, 2 * (self.mueff - 2 + 1 / self.mueff) / ((n + 2) ** 2 + self.mueff))
        f = (n + 2) / 3.0  # separable: faster learning of the diagonal
        self.c1, self.cmu = min(1.0, c1 * f), min(1 - min(1.0, c1 * f), cmu * f)
        self.chin = math.sqrt(n) * (1 - 1 / (4 * n) + 1 / (21 * n * n))
        self.d2 = [1.0] * n
        self.ps = [0.0] * n
        self.pc = [0.0] * n
        self.gen = 0

    def ask(self):
        self.ys = []
        xs = []
        for _ in range(self.lam):
            y = [math.sqrt(self.d2[i]) * self.rng.gauss(0, 1) for i in range(self.n)]
            self.ys.append(y)
            xs.append([self.m[i] + self.sigma * y[i] for i in range(self.n)])
        return xs

    def tell(self, costs):
        n = self.n
        order = sorted(range(self.lam), key=lambda k: costs[k])[: self.mu]
        yw = [sum(self.w[j] * self.ys[order[j]][i] for j in range(self.mu)) for i in range(n)]
        self.m = [self.m[i] + self.sigma * yw[i] for i in range(n)]
        a = math.sqrt(self.cs * (2 - self.cs) * self.mueff)
        self.ps = [(1 - self.cs) * self.ps[i] + a * yw[i] / math.sqrt(self.d2[i]) for i in range(n)]
        norm_ps = math.sqrt(sum(p * p for p in self.ps))
        self.gen += 1
        hs = norm_ps / math.sqrt(1 - (1 - self.cs) ** (2 * self.gen)) < (1.4 + 2 / (n + 1)) * self.chin
        b = math.sqrt(self.cc * (2 - self.cc) * self.mueff)
        self.pc = [(1 - self.cc) * self.pc[i] + (b * yw[i] if hs else 0.0) for i in range(n)]
        for i in range(n):
            rank_mu = sum(self.w[j] * self.ys[order[j]][i] ** 2 for j in range(self.mu))
            self.d2[i] = ((1 - self.c1 - self.cmu) * self.d2[i]
                          + self.c1 * (self.pc[i] ** 2 + (0 if hs else self.cc * (2 - self.cc) * self.d2[i]))
                          + self.cmu * rank_mu)
        self.sigma *= math.exp((self.cs / self.ds) * (norm_ps / self.chin - 1))


def decode(space, u):
    return {k: lo + min(max(x, 0.0), 1.0) * (hi - lo) for (k, (_, lo, hi)), x in zip(space.items(), u)}


def seeds_arg(s):
    if "-" in s:
        a, b = s.split("-")
        return list(range(int(a), int(b) + 1))
    return [int(x) for x in s.split(",")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--track", default="road", choices=list(PARAMS))
    ap.add_argument("--generations", type=int, default=25)
    ap.add_argument("--popsize", type=int, default=12)
    ap.add_argument("--sigma", type=float, default=0.18)
    ap.add_argument("--seeds", default="42,7", help="driver mixes raced per candidate: 1,2,3 or 100-109")
    ap.add_argument("--cars", type=int, default=14)
    ap.add_argument("--laps", type=int, default=2)
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    ap.add_argument("--evaluate", help="only race this .properties file (or 'default') on the seeds")
    args = ap.parse_args()
    if not os.path.exists(os.path.join(ROOT, "build", "eval.args")):
        sys.exit("run ./gradlew evalArgs first")
    space = PARAMS[args.track]
    seeds = seeds_arg(args.seeds)
    run = os.path.join(ROOT, "tools", "train", "runs", f"{args.track}-{time.strftime('%Y%m%d-%H%M%S')}")
    work = os.path.join(run, "work")
    os.makedirs(work, exist_ok=True)
    global ARGS_FILE
    ARGS_FILE = snapshot_classes(run)
    pool = cf.ThreadPoolExecutor(args.workers)

    def evaluate(params_list, tag):
        jobs = {}
        for c, p in enumerate(params_list):
            for s in seeds:
                jobs[(c, s)] = pool.submit(race, args.track, p, s, args.cars, args.laps, f"{tag}-{c}-{s}", work)
        return [[jobs[(c, s)].result() for s in seeds] for c in range(len(params_list))]

    if args.evaluate:
        params = {k: v[0] for k, v in space.items()}
        if args.evaluate != "default":
            with open(args.evaluate) as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#") and "=" in line:
                        k, v = line.split("=", 1)
                        params[k.strip()] = float(v)
        res = evaluate([params], "eval")[0]
        print(json.dumps({"cost": round(cost(res, args.laps), 3), **summary(res)}, indent=1))
        return

    x0 = [(d - lo) / (hi - lo) for d, lo, hi in space.values()]
    es = SepCMA(x0, args.sigma, args.popsize, random.Random(1))
    base = evaluate([decode(space, x0)], "base")[0]
    best_cost, best = cost(base, args.laps), decode(space, x0)
    print(f"baseline cost {best_cost:.2f} {summary(base)}", flush=True)
    log = open(os.path.join(run, "log.jsonl"), "a")
    log.write(json.dumps({"gen": 0, "cost": best_cost, "params": best, **summary(base)}) + "\n")
    for g in range(1, args.generations + 1):
        t0 = time.time()
        xs = es.ask()
        cands = [decode(space, x) for x in xs]
        results = evaluate(cands, f"g{g}")
        # out-of-range coordinates are clipped for the race and penalized for the search
        costs = [cost(r, args.laps) + 10 * sum(max(0, -x) + max(0, x - 1) for x in u) for r, u in zip(results, xs)]
        es.tell(costs)
        k = min(range(len(costs)), key=costs.__getitem__)
        if costs[k] < best_cost:
            best_cost, best = costs[k], cands[k]
            with open(os.path.join(run, "best.properties"), "w") as f:
                f.write(f"# tools/train/tune_ai.py, generation {g}, cost {best_cost:.3f}, seeds {args.seeds}\n")
                for name, v in best.items():
                    f.write(f"{name}={v:.5g}\n")
        log.write(json.dumps({"gen": g, "cost": costs[k], "best": best_cost, "sigma": es.sigma,
                              "params": cands[k], **summary(results[k])}) + "\n")
        log.flush()
        print(f"gen {g:3d}  best-of-gen {costs[k]:.2f}  best {best_cost:.2f}  sigma {es.sigma:.3f}  "
              f"{summary(results[k])}  {time.time() - t0:.0f}s", flush=True)
    print("best parameters:", os.path.join(run, "best.properties"))


if __name__ == "__main__":
    main()
