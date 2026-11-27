"""Racing Sim bot SDK for Python (standard library only). See bots/PROTOCOL.md.

    from racingsim_bot import Bot, Control

    class MyBot(Bot):
        name = "MyBot"
        def on_tick(self, state):
            return Control(steer=0.0, throttle=1.0, brake=0.0)

    MyBot().run()          # connects to 127.0.0.1:4510 (or RACINGSIM_BOTS / --port)
"""
import json
import os
import socket
import sys
import time
from dataclasses import dataclass, field

PROTOCOL = 1
DEFAULT_PORT = 4510


@dataclass
class Control:
    """steer -1 (full left) .. 1 (full right), throttle and brake 0..1, shift -1 / 0 / +1 (manual gearbox only).
    debug: optional lines to draw in practice, [[s1, d1, s2, d2, "#rrggbb"], ...] in track coordinates."""
    steer: float = 0.0
    throttle: float = 0.0
    brake: float = 0.0
    shift: int = 0
    debug: list = field(default_factory=list)


class Track:
    """The `track` message: centerline samples every `step` m (s, x, y, z, heading, curvature, minD, maxD, bank, grade)."""

    def __init__(self, msg):
        self.layout = msg["layout"]
        self.length = msg["length"]
        self.step = msg["step"]
        cols = msg["columns"].split(",")
        self.samples = [dict(zip(cols, row)) for row in msg["samples"]]
        self._n = len(self.samples)

    def wrap(self, s):
        return s % self.length

    def sample(self, s):
        """The sample at or before s (no interpolation)."""
        return self.samples[int(self.wrap(s) / self.step) % self._n]

    def interp(self, s, key):
        s = self.wrap(s)
        i = int(s / self.step) % self._n
        t = (s - i * self.step) / self.step
        a, b = self.samples[i][key], self.samples[(i + 1) % self._n][key]
        return a + (b - a) * t


class Bot:
    """Override on_tick (and optionally on_track / on_event / on_finish)."""
    name = "Bot"
    color1 = None  # "#rrggbb" livery colors (optional)
    color2 = None

    def __init__(self):
        self.track = None
        self.welcome = None

    # ---- override these ----
    def on_welcome(self, welcome):
        pass

    def on_track(self, track):
        pass

    def on_tick(self, state):
        return Control()

    def on_event(self, event):
        pass

    def on_finish(self, result):
        pass

    # ---- connection loop ----
    def run(self, host="127.0.0.1", port=None, retry_seconds=30.0):
        port = port or _port_from_args()
        deadline = time.time() + retry_seconds
        while True:
            try:
                sock = socket.create_connection((host, port))
                break
            except OSError:
                if time.time() > deadline:
                    sys.exit(f"{self.name}: no game on {host}:{port}")
                time.sleep(0.5)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        f = sock.makefile("r", encoding="utf-8", newline="\n")
        hello = {"type": "hello", "protocol": PROTOCOL, "name": self.name}
        if self.color1:
            hello["color1"] = self.color1
        if self.color2:
            hello["color2"] = self.color2
        _send(sock, hello)
        print(f"{self.name}: connected to {host}:{port}", flush=True)
        try:
            self._loop(sock, f)
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
            print(f"{self.name}: the game closed the connection", flush=True)
        sock.close()

    def _loop(self, sock, f):
        for line in f:
            msg = json.loads(line)
            kind = msg.get("type")
            if kind == "state":
                c = self.on_tick(msg) or Control()
                out = {"type": "control", "tick": msg["tick"], "steer": float(c.steer), "throttle": float(c.throttle),
                       "brake": float(c.brake), "shift": int(c.shift)}
                if c.debug:
                    out["debug"] = {"lines": c.debug}
                _send(sock, out)
            elif kind == "welcome":
                self.welcome = msg
                self.on_welcome(msg)
            elif kind == "track":
                self.track = Track(msg)
                self.on_track(self.track)
            elif kind == "event":
                self.on_event(msg)
            elif kind == "bye":
                self.on_finish(msg)
                break


def _send(sock, obj):
    sock.sendall((json.dumps(obj, separators=(",", ":")) + "\n").encode("utf-8"))


def _port_from_args():
    for i, a in enumerate(sys.argv):
        if a == "--port" and i + 1 < len(sys.argv):
            return int(sys.argv[i + 1])
        if a.startswith("--port="):
            return int(a.split("=", 1)[1])
    env = os.environ.get("RACINGSIM_BOTS", "")
    return int(env) if env.isdigit() else DEFAULT_PORT
