"""The baseline bot: a few numbers to tune, the same algorithm in every SDK language.

Steering: aims at a point LOOK_BASE + LOOK_PER_SPEED * speed ahead on its chosen lane, allowing for
how the road bends on the way there, plus damping on the yaw rate.
Speed: the fastest speed each `ahead` sample's curvature allows, with the grip of the tires
(GRIP_USE x tireMu x g) plus the downforce, which grows with the square of the speed (AERO_GRIP x v^2):
v^2 |k| = mu g + AERO_GRIP v^2; less what braking at BRAKE_DECEL can shed before it. Full throttle
below that speed, brakes above.
Traffic: moves over to pass a car close ahead, and lifts when it is about to run into one.

    python bots/python/examples/baseline.py [--port 4510]
"""
import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from racingsim_bot import Bot, Control  # noqa: E402

GRIP_USE = 0.85                             # share of the tires' grip planned on
AERO_GRIP = {"road": 0.0025, "oval": 0.0010}  # extra cornering grip per (m/s)^2 from the downforce
BRAKE_DECEL = {"road": 16.0, "oval": 9.0}   # m/s^2 planned under braking
LOOK_BASE, LOOK_PER_SPEED = 8.0, 0.35       # steering look-ahead (m, + per m/s)
STEER_GAIN, YAW_DAMP = 2.2, 0.25            # steer per rad of heading error, per rad/s of yaw rate error
PASS_OFFSET = 3.2                           # m beside a car being passed
SPEED_MARGIN = 0.5                          # m/s over the target before braking


class Baseline(Bot):
    name = "Baseline"
    color1, color2 = "#1e88e5", "#ffffff"

    def __init__(self):
        super().__init__()
        self.lane = 0.0

    def on_tick(self, st):
        car = st["car"]
        layout = self.track.layout if self.track else "road"
        v = max(car["speed"], 1.0)
        s, d = car["s"], car["d"]

        # ---- the lane: the middle of the road, or beside a car close ahead ----
        target_d = 0.0
        for o in st["opponents"]:
            if 0 < o["ds"] < 30 and abs(o["dd"]) < 2.5 and o["relSpeed"] < 0:
                side = -1.0 if o["d"] > 0 else 1.0      # go by on the roomier side
                target_d = o["d"] + side * PASS_OFFSET
                break
        ahead = st["ahead"]
        lo = max(a[2] for a in ahead[:6]) + 1.8
        hi = min(a[3] for a in ahead[:6]) - 1.8
        target_d = min(max(target_d, lo), hi) if lo < hi else 0.0
        self.lane += (target_d - self.lane) * 0.15

        # ---- steering: aim at the lane look_ahead metres on ----
        look = LOOK_BASE + LOOK_PER_SPEED * v
        k = _curv_at(ahead, look * 0.5)
        lateral = self.lane - d - k * look * look / 2.0      # the road bends left (k > 0): the aim point is further left
        want_heading = math.atan2(lateral, look)              # + = right
        want_yaw = -k * v                                      # yaw rate needed to follow the bend (+ right)
        steer = STEER_GAIN * (want_heading - car["headingErr"]) + YAW_DAMP * (want_yaw - car["yawRate"])
        steer = max(-1.0, min(1.0, steer))

        # ---- speed ----
        mu_g = GRIP_USE * self.welcome["car"]["tireMu"] * 9.81
        aero = AERO_GRIP.get(layout, 0.001)
        dec = BRAKE_DECEL.get(layout, 12.0)
        target = 200.0
        for x, kk, _, _ in [(0.0, ahead[0][1], 0, 0)] + [tuple(a) for a in ahead]:
            room = abs(kk) - aero
            vc = math.sqrt(mu_g / room) if room > 1e-5 else 200.0
            target = min(target, math.sqrt(vc * vc + 2 * dec * x))
        for o in st["opponents"]:   # don't drive into the car ahead in our lane
            if 0 < o["ds"] < 25 and abs(o["dd"]) < 2.2:
                target = min(target, v + o["relSpeed"] + (o["ds"] - 8) * 0.5)
        if car["speed"] < target:
            throttle, brake = min(1.0, (target - car["speed"]) * 0.5 + 0.3), 0.0
        elif car["speed"] > target + SPEED_MARGIN:
            throttle, brake = 0.0, min(1.0, (car["speed"] - target) * 0.25)
        else:
            throttle, brake = 0.2, 0.0
        if st["race"]["phase"] != "green":
            throttle, brake = 0.0, 1.0 if st["race"]["phase"] == "lights" else brake
        return Control(steer=steer, throttle=throttle, brake=brake,
                       debug=[[s, d, s + look, self.lane, "#00ff00"]])

    def on_event(self, ev):
        if ev["event"] == "lapComplete":
            print(f"lap {ev['lap']}: {ev['lapTime']:.2f}s  P{ev['position']}", flush=True)

    def on_finish(self, result):
        for c in result["order"]:
            if c["bot"]:
                print(f"finished P{c['position']}  best lap {c['bestLap']}  contacts {c['contacts']}", flush=True)


def _curv_at(ahead, x):
    """Curvature x metres ahead (linear between the samples)."""
    prev = ahead[0]
    for a in ahead:
        if a[0] >= x:
            if a is prev:
                return a[1]
            t = (x - prev[0]) / (a[0] - prev[0])
            return prev[1] + (a[1] - prev[1]) * t
        prev = a
    return ahead[-1][1]


if __name__ == "__main__":
    Baseline().run()
