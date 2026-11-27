# Write your own AI driver

A bot is a small program that drives one car. Each one runs as its own process, so a bot that
crashes or hangs never takes the game down. It talks to the game over a local TCP socket, one JSON
line per message ([PROTOCOL.md](PROTOCOL.md)).

The same bot races in both games: this Java game and the Unreal version
([unreal-racing-sim](https://github.com/fplatten/unreal-racing-sim)).

| Language | SDK | Baseline bot | Needs |
|---|---|---|---|
| Python 3.9+ | `python/racingsim_bot/` | `python/examples/baseline.py` | nothing else |
| Java 21 | `java/src/racingsim/bot/` | `java/examples/Baseline.java` | nothing else |
| C# | `csharp/Bot.cs` | `csharp/Baseline.cs` | .NET 8 SDK |
| Lua 5.3 / 5.4 / LuaJIT | `lua/racingsim_bot.lua` | `lua/baseline.lua` | LuaSocket (`luarocks install luasocket`) |

The four baselines are the same algorithm with the same numbers. The Python and Java ones lap the
road course in exactly the same time (120.32 s alone in the Java game, 120.01 s in the Unreal one).
The C# and Lua versions have not been run yet.

## Quick start

1. Start a race that waits for a bot. The fastest way is headless, which races as fast as the CPU
   allows:
   ```
   ./gradlew botRace -Ptrack=road -Plaps=1 -Popponents=7
   ```
2. When it prints `Waiting for 1 bot(s)...`, start your bot. Use one of these:
   ```
   python bots/python/examples/baseline.py

   javac -d bots/java/out bots/java/src/racingsim/bot/*.java bots/java/examples/Baseline.java
   java -cp bots/java/out Baseline

   dotnet run --project bots/csharp

   cd bots/lua && lua baseline.lua
   ```
3. The race prints the classification, and the bot gets it in its `bye` message.
   - `-Presults=out.json` also writes it to a file.
   - `-Pseed=N` races against another mix of AI drivers.
   - `-Pstart=1` puts the bot on pole instead of at the back.

### In the game, to watch it

- **Java:** `./gradlew run -Pbots=4510 -Ptrack=road`, or `RACINGSIM_BOTS=4510` with the packaged game.
- **Unreal:** `RacingSim.exe -Bots=4510`.

Connect your bots before you start the race. The menu lists them, and each one takes an AI car,
from the back of the grid forwards.

- To have a bot drive your own car: `RACINGSIM_BOT_PLAYER=1` (Java) or `-BotPlayer` (Unreal).
- Lines your bot sends in `control.debug.lines` are drawn over the track. The baselines draw where they are steering to.
- In the game nothing waits for a bot. If it goes quiet for 2 s, the built-in AI takes its car back.

## Writing a bot (Python)
```python
from racingsim_bot import Bot, Control

class MyBot(Bot):
    name = "MyBot"

    def on_track(self, track):           # once per race: the whole lap, every 5 m
        self.curv = [smp["curvature"] for smp in track.samples]

    def on_tick(self, st):               # 30 times a second
        car = st["car"]                   # s, d, speed, headingErr, yawRate, ...
        ahead = st["ahead"]               # [distance, curvature, minD, maxD] up to 300 m ahead
        steer = -0.8 * car["d"] / 5 - 1.5 * car["headingErr"]
        return Control(steer=steer, throttle=1.0 if car["speed"] < 60 else 0.0, brake=0.0)

MyBot().run()
```
- d, `headingErr`, `yawRate` and `steer` are all **positive to the right**.
- Curvature is **positive for a left-hand bend**.

## Making it faster

The baseline leaves a lot on the table. The built-in AI's best laps are about 113 s against the
baseline's 118. Some ideas:

- **A racing line.** Plan one from the `track` message: wide on entry, apex, wide on exit. Follow it
  instead of the middle of the road.
- **Better speed targets.** The baseline's grip figures (`GRIP_USE`, `AERO_GRIP`) are rough and
  `BRAKE_DECEL` is cautious. Measure what the car really does: the state's `gLat` and `gLon` tell you.
- **Racecraft.** Use the tow (`draft`) on the straights, and brake later than the car ahead down
  the inside of a corner. Use `opponents[].ttc` to stay out of trouble.

## Training drivers

[tools/train/](../tools/train/) uses the same protocol to train AI drivers:

- `tune_ai.py` tunes the built-in AI's parameters with batches of headless races (CMA-ES).
- `racingsim_env.py` is a reinforcement-learning environment: Gymnasium API, lockstep, and
  training resets anywhere on the lap.
- `train_ppo.py` trains a neural driver with PPO, warm-started from the baseline bot.
