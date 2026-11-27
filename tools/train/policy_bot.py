"""Races a trained policy (policy.json from train_ppo.py) as an ordinary bot: standard library only,
so it runs against `./gradlew botRace`, the Java game and the Unreal game alike.

    python tools/train/policy_bot.py tools/train/runs/ppo-road-<time>/policy.json [--port 4510] [--name Neural]
"""
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "..", "bots", "python"))
from racingsim_bot import Bot, Control  # noqa: E402
from racingsim_env import observe  # noqa: E402


class Policy:
    def __init__(self, path):
        with open(path) as f:
            self.layers = json.load(f)["layers"]

    def __call__(self, x):
        for layer in self.layers:
            x = [sum(w * v for w, v in zip(row, x)) + b for row, b in zip(layer["w"], layer["b"])]
            if layer["act"] == "tanh":
                x = [math.tanh(v) for v in x]
        return [max(-1.0, min(1.0, v)) for v in x]


class PolicyBot(Bot):
    def __init__(self, policy, name):
        super().__init__()
        self.policy = policy
        self.name = name
        self.color1, self.color2 = "#8e24aa", "#ffffff"

    def on_tick(self, st):
        if st["race"]["phase"] == "lights":
            return Control(brake=1.0)
        steer, pedal = self.policy(observe(st))
        return Control(steer=steer, throttle=max(pedal, 0.0), brake=max(-pedal, 0.0))

    def on_event(self, ev):
        if ev["event"] == "lapComplete":
            print(f"lap {ev['lap']}: {ev['lapTime']:.2f}s  P{ev['position']}", flush=True)
        elif ev["event"] == "recovered":
            print("crashed: towed back", flush=True)

    def on_finish(self, result):
        for c in result["order"]:
            if c["bot"]:
                print(f"finished P{c['position']}  best lap {c['bestLap']}  contacts {c['contacts']}", flush=True)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    name = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--name=")), "Neural")
    PolicyBot(Policy(args[0]), name).run()
