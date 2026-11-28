"""A reinforcement-learning environment over the bot protocol (bots/PROTOCOL.md).

Each environment starts its own headless Java race (BotRace, lockstep and training mode: the race
waits for every action, and the agent may move its car with `reset`), so it is deterministic and
runs as fast as the CPU allows. Several can run side by side on different ports.

    ./gradlew evalArgs                                  # once: build/eval.args
    python tools/train/racingsim_env.py --check         # drives the baseline bot through the env

With gymnasium installed (pip install gymnasium) RacingSimEnv is a gymnasium.Env (Box spaces), ready
for stable-baselines3 and the like (tools/train/train_ppo.py); without it, it has the same
reset() / step() API and no other dependency.

Observation (OBS_SIZE floats, all roughly -1..1):
  car: speed, vLat, yawRate, headingErr, d, dRate, gLat, gLon, slipFront, slipRear, wheelsOnTrack, draft, rpm, gear
  ahead: 20 x (curvature, minD, maxD) at +10 .. +300 m
  rays: 19 wall distances
  opponents: 8 x (ds, dd, relSpeed, present)
Action: [steer -1..1 (+ right), pedal -1..1 (> 0 throttle, < 0 brake)].
Reward: metres of progress / 10 per step, -0.5 per step with two or more wheels off the tarmac,
-5 per contact, -10 per track-limits penalty (on top of the power cut it brings), -20 and the end of
the episode for a crash (the car is towed back: "recovered"). strict=True: a track-limits penalty
ends the episode (-30), a crash costs 50, a contact 10, and tailgating (time to contact under 1 s
with a car right ahead) 0.2 a step.
"""
import json
import math
import os
import random
import socket
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OBS_SIZE = 14 + 20 * 3 + 19 + 8 * 4

try:  # optional
    import gymnasium as _gym
    import numpy as _np
except ImportError:  # pragma: no cover
    _gym = None
    _np = None

_Base = _gym.Env if _gym else object


def observe(st):
    """The state message as a flat list of OBS_SIZE floats."""
    c = st["car"]
    redline = c.get("redline") or 12000
    o = [c["speed"] / 100, c["vLat"] / 10, c["yawRate"], c["headingErr"], c["d"] / 10, c["dRate"] / 10,
         c["gLat"] / 5, c["gLon"] / 5, c["slipFront"], c["slipRear"], c["wheelsOnTrack"] / 4, c["draft"],
         c["rpm"] / redline, c["gear"] / 8]
    for a in st["ahead"]:
        o += [a[1] * 100, a[2] / 20, a[3] / 20]
    o += [r / 200 for r in st["rays"]]
    opp = st["opponents"]
    for k in range(8):
        if k < len(opp):
            p = opp[k]
            o += [p["ds"] / 200, p["dd"] / 10, p["relSpeed"] / 20, 1.0]
        else:
            o += [0.0, 0.0, 0.0, 0.0]
    return [max(-5.0, min(5.0, float(x))) for x in o]


class RacingSimEnv(_Base):
    metadata = {"render_modes": []}

    def __init__(self, track="road", port=4600, opponents=0, max_steps=3000, control_hz=30, seed=0,
                 start_spread=True, java="java", strict=False):
        self.track, self.port, self.opponents = track, port, opponents
        self.max_steps, self.control_hz, self.start_spread = max_steps, control_hz, start_spread
        # strict: a track-limits penalty ends the episode (-30), a crash costs 50, a contact 10, and
        # closing on a car right ahead (time to contact under 1 s) 0.2 a step
        self.strict = strict
        self.rng = random.Random(seed)
        self.proc = None
        self.sock = None
        self.reader = None
        self.track_msg = None
        self.welcome = None
        self.state = None
        self.steps = 0
        self.java = java
        if _gym:
            self.observation_space = _gym.spaces.Box(-5.0, 5.0, (OBS_SIZE,), dtype=_np.float32)
            self.action_space = _gym.spaces.Box(-1.0, 1.0, (2,), dtype=_np.float32)

    # ---- the race process
    def _start(self):
        args_file = os.path.join(ROOT, "build", "eval.args")
        if not os.path.exists(args_file):
            raise RuntimeError("run ./gradlew evalArgs first")
        cmd = [self.java, "@" + args_file, f"-Dtrack={self.track}", f"-Dport={self.port}", "-DbotCount=1",
               f"-Dopponents={self.opponents}", "-Dlockstep=true", "-Dtraining=true", "-Dlaps=100000",
               "-Dseconds=100000000", f"-DbotHz={self.control_hz}", "-Dstart=1", "com.racingsim.bot.BotRace"]
        log = open(os.path.join(ROOT, "build", f"env-{self.port}.log"), "w")
        self.proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        deadline = time.time() + 60
        while True:
            try:
                self.sock = socket.create_connection(("127.0.0.1", self.port))
                break
            except OSError:
                if time.time() > deadline or self.proc.poll() is not None:
                    raise RuntimeError(f"the race on port {self.port} did not start (build/env-{self.port}.log)")
                time.sleep(0.3)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.reader = self.sock.makefile("r", encoding="utf-8", newline="\n")
        self._send({"type": "hello", "protocol": 1, "name": "Learner"})
        # welcome, track, then the states on the grid until the lights go out
        while True:
            m = self._recv()
            if m["type"] == "welcome":
                self.welcome = m
            elif m["type"] == "track":
                self.track_msg = m
            elif m["type"] == "state":
                if m["race"]["phase"] == "green":
                    # one throw-away reset, so every episode starts from a car that has been reset
                    # the same way (the first one would carry the grid's engine state)
                    self._send({"type": "reset", "s": 100.0, "d": 0.0, "speed": 20.0})
                    self.state, _ = self._next_state()
                    return
                self._send({"type": "control", "tick": m["tick"], "steer": 0, "throttle": 0, "brake": 1})

    def _send(self, obj):
        self.sock.sendall((json.dumps(obj, separators=(",", ":")) + "\n").encode())

    def _recv(self):
        line = self.reader.readline()
        if not line:
            raise RuntimeError("the race closed the connection")
        return json.loads(line)

    def _next_state(self):
        """Reads up to the next state; returns it and the events on the way."""
        events = []
        while True:
            m = self._recv()
            if m["type"] == "state":
                return m, events
            if m["type"] == "event":
                events.append(m)

    # ---- the API
    def reset(self, seed=None, options=None):
        if _gym:
            super().reset(seed=seed)
        if seed is not None:
            self.rng = random.Random(seed)
        if self.proc is None:
            self._start()
        length = self.track_msg["length"]
        s = self.rng.uniform(0, length) if self.start_spread else 0.0
        speed = self.rng.uniform(10, 50) if self.start_spread else 0.0
        self._send({"type": "reset", "s": s, "d": self.rng.uniform(-2, 2), "speed": speed})
        self.state, _ = self._next_state()
        self.steps = 0
        obs = observe(self.state)
        info = {"s": self.state["car"]["s"]}
        return (_np.asarray(obs, dtype=_np.float32) if _np else obs), info

    def step(self, action):
        steer = max(-1.0, min(1.0, float(action[0])))
        pedal = max(-1.0, min(1.0, float(action[1])))
        prev = self.state
        self._send({"type": "control", "tick": prev["tick"], "steer": steer,
                    "throttle": max(pedal, 0.0), "brake": max(-pedal, 0.0)})
        self.state, events = self._next_state()
        self.steps += 1
        length = self.track_msg["length"]
        ds = (self.state["car"]["s"] - prev["car"]["s"] + length / 2) % length - length / 2
        reward = ds / 10.0
        terminated = False
        if self.state["car"]["wheelsOnTrack"] < 3:
            reward -= 0.5
        if self.strict:
            # tailgating: closing fast on a car right ahead in our lane
            for o in self.state["opponents"]:
                if 0 < o["ds"] < 30 and abs(o["dd"]) < 2.2 and 0 <= o["ttc"] < 1.0:
                    reward -= 0.2
        for e in events:
            if e["event"] == "contact":
                reward -= 10.0 if self.strict else 5.0
            elif e["event"] == "trackLimits":
                reward -= 30.0 if self.strict else 10.0
                terminated = terminated or self.strict
            elif e["event"] == "recovered":
                reward -= 50.0 if self.strict else 20.0
                terminated = True
        truncated = self.steps >= self.max_steps
        obs = observe(self.state)
        info = {"s": self.state["car"]["s"], "speed": self.state["car"]["speed"], "events": events}
        return (_np.asarray(obs, dtype=_np.float32) if _np else obs), reward, terminated, truncated, info

    def close(self):
        try:
            if self.sock:
                self.sock.close()
        finally:
            if self.proc and self.proc.poll() is None:
                self.proc.terminate()
                try:
                    self.proc.wait(10)
                except subprocess.TimeoutExpired:
                    self.proc.kill()
            self.proc = None


def _check():
    """Drives the baseline bot's policy through the env for 600 steps from a few random places."""
    sys.path.insert(0, os.path.join(ROOT, "bots", "python", "examples"))
    sys.path.insert(0, os.path.join(ROOT, "bots", "python"))
    from baseline import Baseline
    env = RacingSimEnv(seed=1)
    pilot = Baseline()
    t0 = time.time()
    try:
        for ep in range(3):
            obs, info = env.reset()
            assert len(obs) == OBS_SIZE, len(obs)
            pilot.welcome, pilot.lane = env.welcome, env.state["car"]["d"]
            total, n = 0.0, 0
            for n in range(1, 601):
                c = pilot.on_tick(env.state)
                obs, r, term, trunc, info = env.step([c.steer, c.throttle - c.brake])
                total += r
                if term or trunc:
                    break
            print(f"episode {ep}: from s={info['s']:.0f}, {n} steps, return {total:.1f}, "
                  f"{'crashed' if term else 'ok'}", flush=True)
    finally:
        env.close()
    print(f"{time.time() - t0:.1f} s wall time")


if __name__ == "__main__":
    if "--check" in sys.argv:
        _check()
