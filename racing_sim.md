Create a complete, runnable Java Proof of Concept (POC) for a 3D open-wheel oval racing simulator using jMonkeyEngine 3 (jME3) and Gradle, featuring custom tire/aero physics, AI opponent cars, and aerodynamic drafting.

### Project Requirements & Setup
- Use Gradle (Kotlin or Groovy DSL) targeting Java 21.
- Include the latest stable jMonkeyEngine 3 dependencies (`jme3-core`, `jme3-desktop`, `jme3-lwjgl3`, and `com.github.stephengold:Minie` for physics).
- Organize the code cleanly into modular packages: `app`, `physics`, `track`, `vehicle`, `ai`, and `ui`.

### Core Features to Implement
1. **Track Geometry, Racing Spline & Collisions (`track` package):**
   - Procedurally generate a 2.5-mile (4,023 meters) rectangular oval track with two 5/8-mile (1,005m) straights, two 1/8-mile (201m) short chutes, and four 1/4-mile (402m) turns banked at 9.2 degrees.
   - Generate a continuous 3D mesh with road surface, apron, and outer retaining wall, plus a static rigid-body physics collider (`RigidBodyControl` with mass 0).
   - Provide a parametric track spline / waypoint representation supporting longitudinal track progress ($s$, 0 to 4,023m) and lateral offset ($d$ from centerline) for AI navigation, drafting calculations, and lap/position tracking.
   - Add visual speed cues (start/finish yard of bricks strip, distance markers/pylons/catch-fence posts every 50m around the perimeter, and basic sky/lighting).

2. **Open-Wheel Vehicle & Custom Physics (`vehicle` and `physics` packages):**
   - Run vehicle dynamics on a fixed physics timestep (240 Hz) decoupled from rendering.
   - Build a procedural open-wheel car mesh (tub/chassis, front wing, rear wing, 4 wheels) with customizable livery colors, attached to a dynamic `RigidBodyControl` (~780 kg).
   - Implement a 4-wheel raycast suspension system (spring + damper forces per wheel).
   - Implement a custom Pacejka "Magic Formula" (or simplified Brush) tire model calculating lateral and longitudinal grip forces based on slip angle, slip ratio, and vertical wheel load ($F_z$).
   - Implement aerodynamic forces: speed-squared downforce ($F_d = 0.5 * \rho * v^2 * C_L * A$) and drag ($F_{drag}$), plus a 6-speed sequential gearbox (with auto-shift option) and braking.
   - Use the Blender MCP server connection to create the base 3D vehicle

3. **Aerodynamic Drafting / Slipstream System (`physics` package):**
   - Before computing aero forces each physics tick, evaluate every car's position relative to cars ahead of it within a longitudinal wake cone (e.g., up to 60 meters behind and within ±3.5 meters laterally).
   - Calculate a `draftFactor` (0.0 in clean air up to ~0.35 right behind a lead car) that dynamically reduces the trailing car's aerodynamic drag ($C_d$) for slingshot passes, while slightly reducing front downforce ($C_L$) to simulate "dirty air" understeer in corners.

4. **AI Opponents (`ai` package):**
   - Spawn 5–7 AI-controlled open-wheel cars with distinct colors in a rolling-start staggered pack ahead of/around the player.
   - Drive AI cars using the exact same `VehiclePhysics` component as the player (no fake rails), controlled via a Pure Pursuit / lookahead steering controller targeting a dynamic racing line ($d$-offset) and corner-entry target speed profiles.
   - Include basic pack awareness: if an AI car approaches a slower car ahead in the draft, offset its target lateral lane ($d$) smoothly to attempt an overtake while avoiding side-to-side collisions.

5. **Camera & Controls (`app` package):**
   - Support keyboard controls (`W`/`Up` = throttle, `S`/`Down` = brake, `A`/`D` or `Left`/`Right` = speed-sensitive steering, `R` = reset player car to start/finish line, `C` = toggle camera).
   - Provide two camera modes: Cockpit/T-Cam view (locked to chassis with subtle pitch/roll) and Chase Cam.

6. **Telemetry & Race HUD (`ui` package):**
   - Display a clean on-screen HUD using jME3's `BitmapText` showing: Speed (MPH), RPM, Current Gear, Race Position (e.g., `P4 / 8`), Lap Number, Current/Last Lap Time, Lateral G-Force, Tire Slip %, and an active **DRAFT %** indicator showing real-time drag reduction from slipstreaming.

Deliver all files needed (`build.gradle`, `settings.gradle`, `Main.java`, and all supporting classes) so I can immediately run `./gradlew run` and race in the draft on the oval.

