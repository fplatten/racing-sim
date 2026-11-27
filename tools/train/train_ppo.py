"""Trains a neural driver with PPO in the headless Java race (tools/train/racingsim_env.py).

    pip install gymnasium stable-baselines3 torch
    ./gradlew evalArgs
    python tools/train/train_ppo.py --envs 8 --steps 3000000 [--clone 200000] [--opponents 0]

1. Warm start (--clone N): N steps of the baseline bot's driving are recorded through the env and
   the policy's mean action is fitted to them (behaviour cloning), so PPO starts from a car that
   already gets round instead of one that spins at the first corner.
2. PPO on --envs races side by side (one JVM each, ports 4600 + i), from random places on the lap
   (curriculum: solo first; --opponents adds built-in AI traffic once it laps cleanly).
3. Checkpoints and the final model go to tools/train/runs/ppo-<time>/; the policy's weights are also
   written as policy.json (layer sizes, weights, biases, the tanh activation) for a dependency-free
   forward pass in the games.

Not run yet in this repository: it needs the packages above (about 2 GB with torch).
"""
import argparse
import json
import os
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.dirname(__file__))


def make_env(rank, track, opponents):
    def _f():
        from racingsim_env import RacingSimEnv
        return RacingSimEnv(track=track, port=4600 + rank, opponents=opponents, seed=rank)
    return _f


def record_baseline(env, steps):
    """(obs, action) pairs of the baseline bot driving through the env."""
    sys.path.insert(0, os.path.join(ROOT, "bots", "python", "examples"))
    sys.path.insert(0, os.path.join(ROOT, "bots", "python"))
    from baseline import Baseline
    pilot = Baseline()
    obs_l, act_l = [], []
    obs, _ = env.reset()
    pilot.welcome, pilot.lane = env.welcome, env.state["car"]["d"]
    for _ in range(steps):
        c = pilot.on_tick(env.state)
        a = [c.steer, c.throttle - c.brake]
        obs_l.append(obs)
        act_l.append(a)
        obs, _, term, trunc, _ = env.step(a)
        if term or trunc:
            obs, _ = env.reset()
            pilot.lane = env.state["car"]["d"]
    return obs_l, act_l


def clone(model, obs_l, act_l, epochs=20):
    import numpy as np
    import torch
    pol = model.policy
    obs = torch.as_tensor(np.asarray(obs_l, dtype=np.float32), device=pol.device)
    act = torch.as_tensor(np.asarray(act_l, dtype=np.float32), device=pol.device)
    opt = torch.optim.Adam(pol.parameters(), lr=1e-3)
    for ep in range(epochs):
        perm = torch.randperm(len(obs))
        total = 0.0
        for i in range(0, len(obs), 512):
            idx = perm[i:i + 512]
            dist = pol.get_distribution(obs[idx])
            loss = ((dist.distribution.mean - act[idx]) ** 2).mean()
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += float(loss) * len(idx)
        print(f"clone epoch {ep}: mse {total / len(obs):.4f}", flush=True)


def export(model, path):
    """The actor (policy_net + action_net) as plain JSON: [{w: [[..]], b: [..], act: "tanh"|"none"}]."""
    layers = []
    mods = [m for m in model.policy.mlp_extractor.policy_net if hasattr(m, "weight")]
    for m in mods:
        layers.append({"w": m.weight.detach().cpu().tolist(), "b": m.bias.detach().cpu().tolist(), "act": "tanh"})
    a = model.policy.action_net
    layers.append({"w": a.weight.detach().cpu().tolist(), "b": a.bias.detach().cpu().tolist(), "act": "none"})
    with open(path, "w") as f:
        json.dump({"obs": "racingsim_env.observe v1", "layers": layers, "clip": [-1, 1]}, f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--track", default="road")
    ap.add_argument("--envs", type=int, default=8)
    ap.add_argument("--steps", type=int, default=3_000_000)
    ap.add_argument("--clone", type=int, default=200_000)
    ap.add_argument("--opponents", type=int, default=0)
    ap.add_argument("--resume")
    args = ap.parse_args()

    from stable_baselines3 import PPO
    from stable_baselines3.common.callbacks import CheckpointCallback
    from stable_baselines3.common.vec_env import SubprocVecEnv, VecMonitor

    run = os.path.join(ROOT, "tools", "train", "runs", f"ppo-{args.track}-{time.strftime('%Y%m%d-%H%M%S')}")
    os.makedirs(run, exist_ok=True)
    venv = VecMonitor(SubprocVecEnv([make_env(i, args.track, args.opponents) for i in range(args.envs)]))
    policy_kwargs = dict(net_arch=dict(pi=[128, 128], vf=[256, 256]), log_std_init=-1.0)
    if args.resume:
        model = PPO.load(args.resume, env=venv)
    else:
        model = PPO("MlpPolicy", venv, n_steps=1024, batch_size=4096, gamma=0.995, gae_lambda=0.95,
                    learning_rate=3e-4, ent_coef=0.0, clip_range=0.2, policy_kwargs=policy_kwargs, verbose=1)
        if args.clone > 0:
            from racingsim_env import RacingSimEnv
            env = RacingSimEnv(track=args.track, port=4590, seed=99)
            try:
                obs_l, act_l = record_baseline(env, args.clone)
            finally:
                env.close()
            clone(model, obs_l, act_l)
    model.learn(args.steps, callback=CheckpointCallback(200_000, run, name_prefix="ppo"))
    model.save(os.path.join(run, "final"))
    export(model, os.path.join(run, "policy.json"))
    venv.close()
    print("saved", run)


if __name__ == "__main__":
    main()
