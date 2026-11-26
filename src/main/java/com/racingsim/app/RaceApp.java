package com.racingsim.app;

import com.jme3.app.SimpleApplication;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.racingsim.ai.AIDriver;
import com.racingsim.ai.RacingLine;
import com.racingsim.ai.SpeedProfile;
import com.racingsim.audio.CarAudio;
import com.racingsim.physics.FixedStepPhysics;
import com.racingsim.track.LapTracker;
import com.racingsim.track.Scenery;
import com.racingsim.track.TrackBuilder;
import com.racingsim.track.TrackLayout;
import com.racingsim.track.TrackSpline;
import com.racingsim.ui.ConfirmDialog;
import com.racingsim.ui.Hud;
import com.racingsim.ui.MenuScreen;
import com.racingsim.ui.ResultsScreen;
import com.racingsim.ui.SplashScreen;
import com.racingsim.vehicle.Car;
import com.racingsim.vehicle.CarModelFactory;
import com.racingsim.vehicle.Livery;
import com.racingsim.vehicle.VehicleSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The game: shows a splash screen with a loading bar while the world is built step by step (one
 * step per frame so the bar animates), then runs MENU (track, race length, 1-32 opponents, the
 * player's starting position, 0-3 mandatory pit stops) -> RACE (oval: rolling start three abreast from the beginning of the
 * front straight, off the last turn; road course: standing start behind five red lights) -> RESULTS
 * (checkered flag) -> back to the MENU. Changing the TRACK in the menu builds the world again
 * (track, scenery, physics, cars) behind the splash, keeping the other settings.
 * AI cars are created on demand (up to a 33-car field) and reused between races.
 */
public final class RaceApp extends SimpleApplication implements ActionListener {

    private enum State { LOADING, MENU, RACE, RESULTS }

    private static final float RESULTS_DELAY = 2f;
    /** Menu key repeat: initial delay and interval (s) while Left / Right is held. */
    private static final float REPEAT_DELAY = 0.4f, REPEAT_INTERVAL = 0.08f;

    private static final String MENU_LEFT = "MenuLeft", MENU_RIGHT = "MenuRight", MENU_OK = "MenuOk";
    /** Enter is mapped on its own: it is the only key that confirms a prompt (Space does not). */
    private static final String KEY_ENTER = "Enter", KEY_ESC = "Escape";
    private static final String MENU_UP = "MenuUp", MENU_DOWN = "MenuDown";

    private PhysicsSpace space;
    private TrackBuilder.BuiltTrack built;
    private CarModelFactory factory;
    private Car player;
    private final List<Car> aiPool = new ArrayList<>();
    private final Map<Car, AIDriver> aiDrivers = new HashMap<>();
    private final Random rnd = new Random(42);
    private int heldDirection;
    private boolean fieldPending;                   // menu changed the field; waiting for car paint
    private float heldTime, repeatTimer;

    private State state;
    private BulletAppState bullet;
    private TrackSpline track;
    private RacingLine line;
    private RaceDirector director;
    private PlayerInput playerInput;
    private RacingController controller;           // racing wheel / gamepad; null while recording a movie
    private CameraController cameraController;
    private Hud hud;
    private MenuScreen menu;
    private ResultsScreen results;
    private CarAudio audio;
    private com.racingsim.vehicle.TireSmoke tireSmoke;
    private boolean showHelp = true;
    private Environment environment;
    private float finishedFor;
    private float orbitAngle;
    /** Everything that belongs to one track (track, scenery, cars, their sound and smoke): dropped on a change of track. */
    private com.jme3.scene.Node world = new com.jme3.scene.Node("World");
    /** False while the world is being built again for another track (the menu and HUD are kept). */
    private boolean firstLoad = true;

    // ---- startup splash / loading ----
    private record LoadStep(String label, Runnable work) { }
    private final List<LoadStep> loadSteps = new ArrayList<>();
    private SplashScreen splash;
    private int loadIndex, loadFrames, warmupFrames;
    private float splashFade = -1f;
    private static final float SPLASH_FADE_TIME = 0.6f;
    private FixedStepPhysics loop;

    // ---- scripted movie: RACINGSIM_MOVIE=<file.avi> records 30 s at a fixed 30 fps ----
    private final String movieFile = System.getenv("RACINGSIM_MOVIE");
    private static final int MOVIE_FPS = 30, MOVIE_SECONDS = 30, MOVIE_FRAMES_PER_STEP = 7;
    private int movieFrames;
    private float movieMenuTime, movieRaceTime;
    private int movieCue;
    // movie sound: the game's own sound mixed offline, one video frame at a time
    private final java.io.ByteArrayOutputStream moviePcm = new java.io.ByteArrayOutputStream();
    private final float[] mixL = new float[com.racingsim.audio.SoundSynth.RATE / MOVIE_FPS];
    private final float[] mixR = new float[mixL.length];
    // RACINGSIM_MOVIE_LAP=1: run until the player completes lap 1, then fade to black and show the
    // repository link (an outro), instead of stopping after MOVIE_SECONDS
    private final boolean movieLap = System.getenv("RACINGSIM_MOVIE_LAP") != null;
    private static final int MOVIE_LAP_MAX_SECONDS = 180;        // safety stop
    private static final float OUTRO_FADE = 2.0f, OUTRO_TEXT_IN = 1.0f, OUTRO_HOLD = 5.0f;
    private static final String OUTRO_URL = "https://github.com/fplatten/racing-sim";
    private float outroTime = -1f;                                // < 0: no outro yet
    private float movieVolume = 1f;
    private com.jme3.scene.Node outro;
    private com.jme3.material.Material outroBlack;
    private com.jme3.font.BitmapText outroUrl, outroNote;

    // ---- Esc confirm prompts ----
    private ConfirmDialog confirm;
    private boolean confirmOpen;

    // Optional automated screenshots for visual checks: set RACINGSIM_AUTOSHOT=<directory>.
    private final String autoShotDir = System.getenv("RACINGSIM_AUTOSHOT");
    private com.jme3.app.state.ScreenshotAppState screenshots;
    private float elapsed;
    private int shotIndex;

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        setDisplayStatView(false);
        setDisplayFps(false);
        viewPort.setBackgroundColor(new ColorRGBA(0.52f, 0.70f, 0.92f, 1f));
        // Esc no longer quits straight away: it opens a confirm prompt
        if (inputManager.hasMapping(INPUT_MAPPING_EXIT)) inputManager.deleteMapping(INPUT_MAPPING_EXIT);
        if (autoShotDir != null) {
            screenshots = new com.jme3.app.state.ScreenshotAppState(autoShotDir + java.io.File.separator, "shot");
            stateManager.attach(screenshots);
        }

        if (movieFile != null) {
            // fixed-step recording: the game runs at exactly 30 fps of video time
            stateManager.attach(new com.jme3.app.state.VideoRecorderAppState(new java.io.File(movieFile), 0.8f, MOVIE_FPS));
        }
        // the track: -Dtrack=road (or ./gradlew run -Ptrack=road) races on the road course
        TrackSpline.selectLayout(TrackLayout.fromSystemProperty());
        rootNode.attachChild(world);
        startLoading();
    }

    /** Shows a fresh splash (it names the track) and plans the loading steps. */
    private void startLoading() {
        state = State.LOADING;
        loadSteps.clear();
        loadIndex = loadFrames = warmupFrames = 0;
        splashFade = -1f;
        if (splash != null) guiNode.detachChild(splash.getNode());
        splash = new SplashScreen(assetManager, guiFont, settings.getWidth(), settings.getHeight());
        splash.getNode().setLocalTranslation(0, 0, 10f);          // above every other GUI layer
        guiNode.attachChild(splash.getNode());
        planLoading();
    }

    /**
     * The loading work, split into steps that each run in their own frame. At startup it builds
     * everything; on a change of track ({@link #switchTrack}) only the world: the sky, the input,
     * the menu, the HUD and the painted cars are kept.
     */
    private void planLoading() {
        boolean first = firstLoad;
        if (first) {
            loadSteps.add(new LoadStep("Lighting the sky", () ->
                    environment = new Environment(assetManager, rootNode, viewPort)));
        }
        loadSteps.add(new LoadStep("Starting the physics engine", () -> {
            bullet = new BulletAppState(PhysicsSpace.BroadphaseType.DBVT);
            stateManager.attach(bullet);
            space = bullet.getPhysicsSpace();
            space.setGravity(new Vector3f(0, -9.81f, 0));
            loop = FixedStepPhysics.install(space);
        }));
        loadSteps.add(new LoadStep(TrackSpline.isOval() ? "Building the 2.5-mile oval" : "Building the 4.352-mile road course", () -> {
            track = new TrackSpline();
            built = new TrackBuilder(track, assetManager).build(space);
            world.attachChild(built.node());
        }));
        // the road course: woods on the hills, stands at the start, the hairpin, the valley and the chicane
        loadSteps.add(new LoadStep(TrackSpline.isOval() ? "Raising the grandstands" : "Planting the forest", () ->
                world.attachChild(new Scenery(assetManager, track, built.terrain()).build())));
        loadSteps.add(new LoadStep("Preparing your car", () -> {
            if (first) {
                playerInput = new PlayerInput();
                playerInput.register(inputManager);
                if (movieFile == null) {                  // scripted movies are driven without a controller
                    controller = new RacingController();
                    playerInput.setController(controller);
                }
            }
            line = new RacingLine(track);
            director = new RaceDirector(track, line, playerInput);
            factory = new CarModelFactory(assetManager);
            player = new Car("YOU", Livery.forIndex(0), true, VehicleSpec.forTrack(), factory, space,
                    built.collider(), built.groundCollider());
            world.attachChild(player.getNode());
            loop.addListener(director);
        }));
        loadSteps.add(new LoadStep("Tuning the engines", () -> {
            audio = new CarAudio(world, cam, player, audioRenderer != null);
            tireSmoke = new com.racingsim.vehicle.TireSmoke(assetManager, world);
        }));
        loadSteps.add(new LoadStep("Setting up the timing screens", () -> {
            cameraController = new CameraController(cam);
            player.getNode().addControl(cameraController);
            director.setOnPlayerReset(cameraController::snap);
            if (!first) return;
            float w = settings.getWidth(), h = settings.getHeight();
            hud = new Hud(assetManager, guiFont, w, h);
            menu = new MenuScreen(assetManager, guiFont, w, h);
            if (TrackSpline.isRoadCourse()) menu.setDownforce(TrackSpline.layout().defaultDownforce());
            results = new ResultsScreen(assetManager, guiFont, w, h);
            confirm = new ConfirmDialog(assetManager, guiFont, w, h);
            confirm.getNode().setLocalTranslation(0, 0, 20f);
        }));
        // one step per opponent of the default field (more are built on demand from the menu); a
        // change of track brings out the field the menu has
        int defaultOpponents = first ? 7 : menu.opponents();
        for (int i = 0; i < defaultOpponents; i++) {
            int index = i;
            loadSteps.add(new LoadStep("Rolling out the field (" + (i + 1) + "/" + defaultOpponents + ")", () -> aiCar(index)));
        }
        loadSteps.add(new LoadStep("Lining up the grid", () -> {
            if (first) {
                inputManager.addMapping(MENU_LEFT, new KeyTrigger(KeyInput.KEY_LEFT), new KeyTrigger(KeyInput.KEY_A));
                inputManager.addMapping(MENU_RIGHT, new KeyTrigger(KeyInput.KEY_RIGHT), new KeyTrigger(KeyInput.KEY_D));
                inputManager.addMapping(MENU_UP, new KeyTrigger(KeyInput.KEY_UP), new KeyTrigger(KeyInput.KEY_W));
                inputManager.addMapping(MENU_DOWN, new KeyTrigger(KeyInput.KEY_DOWN), new KeyTrigger(KeyInput.KEY_S));
                inputManager.addMapping(MENU_OK, new KeyTrigger(KeyInput.KEY_SPACE));
                inputManager.addMapping(KEY_ENTER, new KeyTrigger(KeyInput.KEY_RETURN), new KeyTrigger(KeyInput.KEY_NUMPADENTER));
                inputManager.addMapping(KEY_ESC, new KeyTrigger(KeyInput.KEY_ESCAPE));
                inputManager.addListener(this, MENU_LEFT, MENU_RIGHT, MENU_UP, MENU_DOWN, MENU_OK, KEY_ENTER, KEY_ESC);
            }
            buildField();
            // paint the rest of the possible field in the background, so adding opponents in the
            // menu doesn't stall for ~1.5 s per car
            for (int i = aiPool.size(); i < MenuScreen.MAX_OPPONENTS; i++) factory.prepaint(Livery.forIndex(i + 1), false);
            director.stopRace();
            bullet.setEnabled(false);
            orbitGrid(0f);            // the next frames render (and compile shaders for) the menu view
        }));
    }

    /** Runs one loading step per frame, then a few warm-up frames, then fades the splash out. */
    private void updateLoading(float tpf) {
        splash.update(tpf);
        loadFrames++;
        if (loadFrames < 3) return;                      // let the splash appear first
        // movie: give each loading step a few frames so the bar is watchable
        if (movieFile != null && loadFrames % MOVIE_FRAMES_PER_STEP != 0) return;
        if (loadIndex < loadSteps.size()) {
            LoadStep step = loadSteps.get(loadIndex);
            step.work().run();
            loadIndex++;
            String next = loadIndex < loadSteps.size() ? loadSteps.get(loadIndex).label() : "Warming up the tires";
            splash.setProgress(loadIndex / (float) (loadSteps.size() + 1), next + "...");
            if (System.getenv("RACINGSIM_SHOT_SPLASH") != null && screenshots != null && loadIndex == loadSteps.size() / 2) {
                screenshots.takeScreenshot();
            }
            return;
        }
        // all built: render a few frames behind the splash so shaders compile before it lifts
        if (warmupFrames++ < 4) return;
        splash.setProgress(1f, "Ready");
        if (splash.update(0f) < 0.999f) return;
        enterMenu();
        setDisplayFps(movieFile == null);
        splashFade = SPLASH_FADE_TIME;
        if (firstLoad) setupAutoShots(player);
        firstLoad = false;
    }

    /**
     * The menu's TRACK row changed: a different track is a different world (its spline, meshes,
     * colliders, racing line, the cars' gearing), so drop the world and build it again on the new
     * track behind the splash, as the Unreal version reloads the level. The menu (with its other
     * settings), the HUD, the sky, the input and the cars' paint are kept.
     */
    private void switchTrack(TrackLayout next) {
        heldDirection = 0;
        System.out.println("Switching track: " + next.key());
        guiNode.detachChild(menu.getNode());
        guiNode.detachChild(hud.getNode());
        guiNode.detachChild(results.getNode());
        audio.dispose();
        tireSmoke.clear();
        player.getNode().removeControl(cameraController);
        rootNode.detachChild(world);
        world = new com.jme3.scene.Node("World");
        rootNode.attachChild(world);
        stateManager.detach(bullet);                 // its physics space (and every body in it) goes too
        aiPool.clear();
        aiDrivers.clear();
        fieldPending = false;
        TrackSpline.selectLayout(next);
        startLoading();
    }
    // ------------------------------------------------------------------ the field
    /** AI opponent {@code i} (0-based), created the first time it is needed and reused afterward. */
    private Car aiCar(int i) {
        while (aiPool.size() <= i) {
            Livery livery = Livery.forIndex(aiPool.size() + 1);
            VehicleSpec spec = VehicleSpec.forTrack();
            Car car = new Car(livery.name(), livery, false, spec, factory, space, built.collider(), built.groundCollider());
            float grip = spec.aiGripBase() + rnd.nextFloat() * 0.10f;   // planned grip
            SpeedProfile profile = new SpeedProfile(track, line, spec, car.getPhysics().getAeroModel(), grip, 13f);
            aiDrivers.put(car, new AIDriver(car, line, profile, rnd.nextFloat()));   // driver type: 0 rookie .. 1 veteran
            aiPool.add(car);
        }
        return aiPool.get(i);
    }

    /** Builds the field from the menu settings: opponents + player at the chosen grid slot. */
    private void buildField() {
        buildField(menu.opponents());
    }

    /** Builds the field with the first {@code opponents} AI cars (fewer than the menu asks while paint is pending). */
    private void buildField(int opponents) {
        int playerSlot = Math.min(menu.startPosition() - 1, opponents);
        List<Car> field = new ArrayList<>();
        List<Car> activeAi = new ArrayList<>();
        int next = 0;
        for (int slot = 0; slot <= opponents; slot++) {
            if (slot == playerSlot) {
                field.add(player);
            } else {
                Car c = aiCar(next++);
                field.add(c);
                activeAi.add(c);
            }
        }
        for (int i = 0; i < aiPool.size(); i++) {
            Car c = aiPool.get(i);
            boolean active = i < opponents;
            if (active && c.getNode().getParent() == null) world.attachChild(c.getNode());
            if (!active && c.getNode().getParent() != null) c.getNode().removeFromParent();
            if (c.getBody().isEnabled() != active) c.getBody().setEnabled(active);
        }
        director.setField(field, aiDrivers);
        director.placeGrid();
        audio.setField(activeAi);
    }

    // ------------------------------------------------------------------ state changes
    private void enterMenu() {
        state = State.MENU;
        director.stopRace();
        bullet.setEnabled(false);                  // field frozen on the grid behind the menu
        director.placeGrid();
        cameraController.setEnabled(false);
        audio.setActive(false);
        setMouseSteering(false);
        guiNode.detachChild(hud.getNode());
        guiNode.detachChild(results.getNode());
        guiNode.attachChild(menu.getNode());
    }

    // ------------------------------------------------------------------ Esc prompts
    private void openConfirm() {
        confirmOpen = true;
        if (state == State.RACE) {
            // pause: no physics ticks means the race clock, cars and AI all stand still
            bullet.setEnabled(false);
            audio.setActive(false);
            inputManager.setCursorVisible(true);
            confirm.show("LEAVE THE RACE?", "The race will be abandoned and you go back to the menu.",
                    "quit the race and return to the menu", "return to the race");
        } else {
            confirm.show("QUIT THE GAME?", "Thanks for racing!", "quit", "return to the game");
        }
        guiNode.attachChild(confirm.getNode());
    }

    private void closeConfirm(boolean resume) {
        confirmOpen = false;
        guiNode.detachChild(confirm.getNode());
        if (resume && state == State.RACE) {
            bullet.setEnabled(true);
            audio.setActive(true);
            inputManager.setCursorVisible(!playerInput.isMouseSteering());
        }
    }

    private void acceptConfirm() {
        closeConfirm(false);
        if (state == State.MENU) stop();
        else enterMenu();
    }

    /**
     * Movie script: splash, then the menu settings are changed on screen (opponents, starting
     * position, downforce), Enter starts the race and the AI drives the player's car; the camera
     * cuts to the T-cam for a few seconds. Stops after {@link #MOVIE_SECONDS}.
     * @return true if the app is stopping
     */
    private boolean movieScript(float tpf) {
        if (++movieFrames > (movieLap ? MOVIE_LAP_MAX_SECONDS : MOVIE_SECONDS) * MOVIE_FPS) {
            stop();
            return true;
        }
        if (movieLap && updateOutro(tpf)) {
            stop();
            return true;
        }
        recordMovieAudio();
        if (state == State.MENU && splashFade <= 0f) {
            movieMenuTime += tpf;
            String[] cues = {
                    "1.0 D", "1.5 R", "1.75 R", "2.0 R", "2.25 R", "2.5 R", "2.75 R",   // 7 -> 13 opponents
                    "3.4 D", "3.8 R", "4.05 R", "4.3 R",                                 // start P6 -> P9
                    "4.9 D", "5.3 D", "5.7 R", "5.95 R", "6.2 R",                        // downforce 3/10
                    "7.2 E"};
            if (movieCue < cues.length) {
                String[] c = cues[movieCue].split(" ");
                if (movieMenuTime >= Float.parseFloat(c[0])) {
                    String key = switch (c[1]) {
                        case "D" -> MENU_DOWN;
                        case "R" -> MENU_RIGHT;
                        default -> KEY_ENTER;
                    };
                    onAction(key, true, tpf);
                    onAction(key, false, tpf);
                    movieCue++;
                }
            }
        }
        if (state == State.RACE) {
            float before = movieRaceTime;
            movieRaceTime += tpf;
            if (before < 9.5f && movieRaceTime >= 9.5f) cameraController.toggle();    // T-cam
            if (before < 14.5f && movieRaceTime >= 14.5f) cameraController.toggle();  // back to chase
            if (movieLap) {                                   // the longer cut: T-cam down the back straight
                if (before < 27f && movieRaceTime >= 27f) cameraController.toggle();
                if (before < 33f && movieRaceTime >= 33f) cameraController.toggle();
            }
        }
        return false;
    }

    /**
     * One-lap movie outro: once the player has completed lap 1, the picture and the sound fade to
     * black over {@link #OUTRO_FADE} s, the repository link fades in and holds, then the recording stops.
     * @return true when the outro is over
     */
    private boolean updateOutro(float tpf) {
        if (outroTime < 0f) {
            if (state != State.RACE || director.entryOf(director.getPlayer()).lapsCompleted() < 1) return false;
            buildOutro();
            outroTime = 0f;
        } else {
            outroTime += tpf;
        }
        float fade = Math.min(1f, outroTime / OUTRO_FADE);
        fade = fade * fade * (3f - 2f * fade);                       // smoothstep
        outroBlack.setColor("Color", new ColorRGBA(0f, 0f, 0f, fade));
        movieVolume = 1f - fade;
        float text = FastMath.clamp((outroTime - OUTRO_FADE) / OUTRO_TEXT_IN, 0f, 1f);
        outroUrl.setColor(new ColorRGBA(1f, 1f, 1f, text));
        outroNote.setColor(new ColorRGBA(0.75f, 0.77f, 0.80f, text));
        return outroTime >= OUTRO_FADE + OUTRO_TEXT_IN + OUTRO_HOLD;
    }

    /** The black overlay and the two lines of text, above the HUD. */
    private void buildOutro() {
        float w = settings.getWidth(), h = settings.getHeight();
        outro = new com.jme3.scene.Node("outro");
        outroBlack = new com.jme3.material.Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        outroBlack.setColor("Color", new ColorRGBA(0f, 0f, 0f, 0f));
        outroBlack.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
        com.jme3.scene.Geometry black = new com.jme3.scene.Geometry("outro-black", new com.jme3.scene.shape.Quad(w, h));
        black.setMaterial(outroBlack);
        outro.attachChild(black);
        float base = h / 900f * guiFont.getCharSet().getRenderedSize();
        outroUrl = outroLine(base * 3.4f, h * 0.5f + base * 3.6f, OUTRO_URL, w);
        outroNote = outroLine(base * 2.2f, h * 0.5f - base * 0.4f, "link in the description", w);
        outro.attachChild(outroUrl);
        outro.attachChild(outroNote);
        outro.setLocalTranslation(0, 0, 30f);                     // above the HUD and every dialog
        guiNode.attachChild(outro);
    }

    private com.jme3.font.BitmapText outroLine(float size, float top, String s, float width) {
        com.jme3.font.BitmapText t = new com.jme3.font.BitmapText(guiFont);
        t.setSize(size);
        t.setText(s);
        t.setColor(new ColorRGBA(1f, 1f, 1f, 0f));
        t.setLocalTranslation((width - t.getLineWidth()) / 2f, top, 1f);
        return t;
    }

    /** Mixes the sound of one video frame (silence before the race) into the movie track. */
    private void recordMovieAudio() {
        if (audio != null && !confirmOpen) {
            audio.mixdown(mixL, mixR);
            for (int i = 0; i < mixL.length; i++) {               // outro fade-out (1 otherwise)
                mixL[i] *= movieVolume;
                mixR[i] *= movieVolume;
            }
        } else {
            java.util.Arrays.fill(mixL, 0f);
            java.util.Arrays.fill(mixR, 0f);
        }
        byte[] b = new byte[mixL.length * 4];
        for (int i = 0; i < mixL.length; i++) {
            short l = (short) Math.round(Math.tanh(mixL[i] * 0.7) * 30000);   // soft limiter
            short r = (short) Math.round(Math.tanh(mixR[i] * 0.7) * 30000);
            b[i * 4] = (byte) l;
            b[i * 4 + 1] = (byte) (l >> 8);
            b[i * 4 + 2] = (byte) r;
            b[i * 4 + 3] = (byte) (r >> 8);
        }
        moviePcm.write(b, 0, b.length);
    }

    @Override
    public void destroy() {
        super.destroy();                     // the video recorder finishes the AVI here
        if (movieFile != null) {
            try {
                com.racingsim.audio.AviAudioMuxer.mux(new java.io.File(movieFile), moviePcm.toByteArray(),
                        com.racingsim.audio.SoundSynth.RATE, MOVIE_FPS);
                System.out.println("Movie written with sound: " + movieFile);
            } catch (java.io.IOException e) {
                System.err.println("Could not add the sound track: " + e);
            }
        }
    }

    /** Movie mode: the AI drives the player's car. */
    private void installMovieAutopilot() {
        SpeedProfile profile = new SpeedProfile(track, line, player.getSpec(), player.getPhysics().getAeroModel(), player.getSpec().aiGripBase() + 0.08f, 13f);
        AIDriver pilot = new AIDriver(player, line, profile, 0.6f);
        pilot.resetForRace();
        director.setPlayerOverride((car, dt) -> pilot.update(dt, director.getCars()));
    }

    private void startRace(int laps) {
        if (fieldPending) {                       // opponents still being painted: wait for them now
            fieldPending = false;
            buildField();
        }
        state = State.RACE;
        guiNode.detachChild(menu.getNode());
        guiNode.detachChild(results.getNode());
        guiNode.attachChild(hud.getNode());
        player.getPhysics().getAeroModel().setDownforceLevel(menu.downforceLevel());
        director.startRace(laps, menu.pitStops());
        tireSmoke.clear();
        if (movieFile != null) installMovieAutopilot();
        bullet.setEnabled(true);
        cameraController.setEnabled(true);
        cameraController.snap();
        audio.setActive(true);
        finishedFor = 0f;
    }

    /** Applies a menu change; field changes are shown live on the grid behind the menu. */
    private void changeSetting(int dir) {
        if (menu.onTrackRow()) {
            menu.change(dir);
            switchTrack(menu.track());
            return;
        }
        if (!menu.change(dir)) return;
        // new opponents whose paint isn't ready yet join the grid once it is (see updatePendingField)
        if (fieldPainted()) buildField();
        else fieldPending = true;
    }

    /** True when every opponent the menu asks for can be built without waiting for its paint. */
    private boolean fieldPainted() {
        return paintedOpponents() == menu.opponents();
    }

    /** How many of the opponents the menu asks for can be built right now (built or painted, in grid order). */
    private int paintedOpponents() {
        int n = Math.min(aiPool.size(), menu.opponents());
        while (n < menu.opponents() && factory.isPainted(Livery.forIndex(n + 1), false)) n++;
        return n;
    }

    /** Rolls pending opponents onto the grid behind the menu as their paint finishes. */
    private void updatePendingField() {
        if (!fieldPending) return;
        int ready = paintedOpponents();
        if (ready == menu.opponents()) {
            fieldPending = false;
            buildField();
        } else if (ready != director.getCars().size() - 1) {
            buildField(ready);
        }
    }

    private void repeatHeldKey(float tpf) {
        if (heldDirection == 0) return;
        // a long frame must not count as a long hold (or fire a burst of repeats)
        tpf = Math.min(tpf, 0.1f);
        heldTime += tpf;
        if (heldTime < REPEAT_DELAY) return;
        repeatTimer += tpf;
        while (repeatTimer >= REPEAT_INTERVAL) {
            repeatTimer -= REPEAT_INTERVAL;
            changeSetting(heldDirection);
        }
    }

    /** Mouse steering captures (hides) the cursor so the mouse can travel freely. */
    private void setMouseSteering(boolean on) {
        playerInput.setMouseSteering(on);
        inputManager.setCursorVisible(!on);
    }

    private void showResults() {
        state = State.RESULTS;
        guiNode.attachChild(results.getNode());
    }

    /**
     * Racing wheel / gamepad buttons: driving controls go to the player input; A, B, Start and the
     * D-pad work the menus and prompts like Enter, Esc and the arrow keys.
     */
    private void onControllerButton(RacingController.Button button, boolean pressed) {
        switch (button) {
            case SHIFT_UP, SHIFT_DOWN, CAMERA, AUTO, RESET -> {
                if (pressed) playerInput.onControllerButton(button);
            }
            case OK -> onAction(KEY_ENTER, pressed, 0f);
            case PAUSE -> onAction(KEY_ESC, pressed, 0f);
            // B backs out of prompts and the results, but never pauses a race by accident
            case BACK -> {
                if (confirmOpen || state != State.RACE) onAction(KEY_ESC, pressed, 0f);
            }
            case UP -> onAction(MENU_UP, pressed, 0f);
            case DOWN -> onAction(MENU_DOWN, pressed, 0f);
            case LEFT -> onAction(MENU_LEFT, pressed, 0f);
            case RIGHT -> onAction(MENU_RIGHT, pressed, 0f);
        }
    }

    @Override
    public void onAction(String name, boolean pressed, float tpf) {
        if (!pressed) {
            if (name.equals(MENU_LEFT) || name.equals(MENU_RIGHT)) heldDirection = 0;
            return;
        }
        if (state == State.LOADING || splashFade > 0f) return;
        if (confirmOpen) {                                 // modal: only Enter / Esc
            if (name.equals(KEY_ENTER)) acceptConfirm();
            else if (name.equals(KEY_ESC)) closeConfirm(true);
            return;
        }
        if (name.equals(KEY_ESC)) {
            heldDirection = 0;
            if (state == State.RESULTS) enterMenu();        // the race is over: straight back to the menu
            else openConfirm();
            return;
        }
        if (name.equals(KEY_ENTER)) name = MENU_OK;
        switch (state) {
            case MENU -> {
                switch (name) {
                    case MENU_UP -> menu.moveRow(-1);
                    case MENU_DOWN -> menu.moveRow(1);
                    case MENU_LEFT, MENU_RIGHT -> {
                        int dir = name.equals(MENU_LEFT) ? -1 : 1;
                        changeSetting(dir);
                        heldDirection = dir;
                        heldTime = 0f;
                        repeatTimer = 0f;
                    }
                    case MENU_OK -> startRace(menu.selectedLaps());
                    default -> { }
                }
            }
            case RESULTS -> {
                if (name.equals(MENU_OK)) enterMenu();
            }
            default -> { }
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (movieFile != null && movieScript(tpf)) return;
        if (state == State.LOADING) {
            updateLoading(tpf);
            return;
        }
        if (splashFade > 0f) {
            splashFade -= tpf;
            splash.setOpacity(Math.max(0f, splashFade / SPLASH_FADE_TIME));
            if (splashFade <= 0f) guiNode.detachChild(splash.getNode());
        }
        if (autoShotDir != null) autoShots(tpf);
        Car player = director.getPlayer();
        if (controller != null) controller.poll(tpf, this::onControllerButton);
        // consume keyboard one-shots in every state so they never fire late
        boolean reset = playerInput.consumeReset();
        boolean camera = playerInput.consumeCamera();
        if (playerInput.consumeHelp()) showHelp = !showHelp;
        if (playerInput.consumeQuality()) environment.toggleQuality();
        boolean auto = playerInput.consumeToggleAuto();
        boolean mouseToggle = playerInput.consumeMouseToggle();
        tireSmoke.update(tpf, director.getCars(), confirmOpen || state == State.MENU);
        if (confirmOpen) return;                           // paused behind the prompt

        if (state == State.MENU) {
            repeatHeldKey(tpf);
            updatePendingField();
            orbitGrid(tpf);
            return;
        }
        audio.update(tpf);
        if (mouseToggle) setMouseSteering(!playerInput.isMouseSteering());
        if (reset) director.requestPlayerReset();
        if (camera) cameraController.toggle();
        if (auto) player.getPhysics().getGearbox().setAutomatic(!player.getPhysics().getGearbox().isAutomatic());

        LapTracker.Entry me = director.entryOf(player);
        String camName = switch (cameraController.getMode()) {
            case CHASE -> "CHASE";
            case T_CAM -> "T-CAM";
            case SHOWCASE, PROFILE -> "SHOWCASE";
        };
        hud.setController(controller != null && controller.isConnected() ? controller.getName() : null);
        hud.setStartLights(director.startLights(), director.greenTime());
        hud.update(player, new Hud.RaceInfo(director.getLapTracker(), me, camName, showHelp, playerInput.isMouseSteering(),
                director.pitOf(player), this::driverTag));

        if (state == State.RACE && me.finished()) {
            finishedFor += tpf;
            if (finishedFor > RESULTS_DELAY) showResults();
        }
        if (state == State.RESULTS) results.update(director.getLapTracker(), me);
    }

    /** Standings tag of a car: its AI driver type (-1 for the human player) and wing level. */
    private Hud.DriverTag driverTag(LapTracker.Entry e) {
        Car c = director.carOf(e);
        if (c == null) return null;
        AIDriver ai = director.driverOf(c);
        int wing = Math.round(c.getPhysics().getAeroModel().getDownforceLevel() * RaceDirector.DOWNFORCE_STEPS);
        return new Hud.DriverTag(ai == null ? -1 : AIDriver.typeIndex(ai.getAggression()), wing);
    }

    /** Slow camera sweep along the field lined up on the grid, from the infield side (menu background). */
    private void orbitGrid(float tpf) {
        orbitAngle += tpf * 0.15f;
        int cars = director.getCars().size();
        float midS = RaceDirector.gridSlot(cars / 2, cars)[0];
        float sweep = FastMath.sin(orbitAngle);
        // (the oval: from the infield; the road course: from the run-off on the left)
        float eyeD = TrackSpline.isOval() ? -13f : Math.max(track.minD(midS) + 2f, -11f);
        Vector3f eye = track.point(midS + 32f * sweep, eyeD + 3f * FastMath.cos(orbitAngle * 0.7f), null).addLocal(0, 4.5f, 0);
        Vector3f center = track.point(midS + 8f * sweep, 0.5f, null);
        cam.setLocation(eye);
        cam.lookAt(center.add(0, 0.4f, 0), Vector3f.UNIT_Y);
    }

    // ------------------------------------------------------------------ automated screenshots
    private void setupAutoShots(Car player) {
        if (autoShotDir == null) return;
        String opp = System.getenv("RACINGSIM_OPPONENTS"), start = System.getenv("RACINGSIM_START");
        String lapsSetting = System.getenv("RACINGSIM_LAPS"), pitsSetting = System.getenv("RACINGSIM_PITS");
        if (opp != null || start != null || lapsSetting != null || pitsSetting != null) {
            menu.configure(lapsSetting != null ? Integer.parseInt(lapsSetting) : menu.selectedLaps(),
                    opp != null ? Integer.parseInt(opp) : menu.opponents(),
                    start != null ? Integer.parseInt(start) : menu.startPosition(),
                    pitsSetting != null ? Integer.parseInt(pitsSetting) : menu.pitStops());
            buildField();
        }
        String df = System.getenv("RACINGSIM_DOWNFORCE");               // slider steps 0..10
        if (df != null) menu.setDownforce(Integer.parseInt(df));
        if (System.getenv("RACINGSIM_SHOT_HIDEGUI") != null) {           // clean frames (splash key art)
            guiNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        }
        if ("menu".equals(System.getenv("RACINGSIM_SHOT_CONFIRM"))) openConfirm();
        if (System.getenv("RACINGSIM_SHOT_MENU") != null) return;      // keep the menu on screen
        if (System.getenv("RACINGSIM_MOUSE_STEER") != null) playerInput.setMouseSteering(true);   // HUD state only
        if (System.getenv("RACINGSIM_AUTOPILOT") != null) {               // AI drives the player car
            SpeedProfile profile = new SpeedProfile(track, line, player.getSpec(), player.getPhysics().getAeroModel(), player.getSpec().aiGripBase() + 0.08f, 13f);
            AIDriver pilot = new AIDriver(player, line, profile, 0.6f);
            pilot.resetForRace();
            director.setPlayerOverride((car, dt) -> pilot.update(dt, director.getCars()));
        }
        startRace(lapsSetting != null ? Integer.parseInt(lapsSetting) : menu.selectedLaps());
        if (System.getenv("RACINGSIM_PIT_DEMO") != null) {
            // the AI pilots the player's car down the pit lane into box 17 (pit stop screenshots)
            SpeedProfile profile = new SpeedProfile(track, line, player.getSpec(), player.getPhysics().getAeroModel(), player.getSpec().aiGripBase() + 0.08f, 13f);
            AIDriver pilot = new AIDriver(player, line, profile, 0.6f);
            player.placeOnTrack(track, TrackSpline.wrapS(-110f), -21.9f, 15f);
            player.updateTrackPosition(track);
            pilot.resetForRace();
            pilot.setPit(director.pitOf(player));
            pilot.requestPit();
            director.setPlayerOverride((car, dt) -> pilot.update(dt, director.getCars()));
            cameraController.snap();
        }

        if (System.getenv("RACINGSIM_SHOT_SKID") != null) {
            // tire smoke screenshots: kick the player's car into a sideways slide (yaw rate, rad/s)
            float yaw = Float.parseFloat(System.getenv("RACINGSIM_SHOT_SKID"));
            player.getBody().setAngularVelocity(new Vector3f(0, yaw, 0));
        }

        String showcase = System.getenv("RACINGSIM_SHOT_CAM");   // e.g. "7.5,-1.2,1.0", "tcam", "sun", "profile", "helmet"
        if (showcase != null && showcase.startsWith("fly:")) {
            // fixed camera in track coordinates: fly:s,d,height,lookS,lookD,lookHeight
            String[] f = showcase.substring(4).split(",");
            player.getNode().removeControl(cameraController);
            Vector3f eye = track.point(Float.parseFloat(f[0]), Float.parseFloat(f[1]), null).addLocal(0, Float.parseFloat(f[2]), 0);
            Vector3f at = track.point(Float.parseFloat(f[3]), Float.parseFloat(f[4]), null).addLocal(0, Float.parseFloat(f[5]), 0);
            cam.setLocation(eye);
            cam.lookAt(at, Vector3f.UNIT_Y);
            cam.setFrustumPerspective(60f, (float) cam.getWidth() / cam.getHeight(), 0.5f, 4000f);
        } else if ("sun".equals(showcase)) {
            player.getNode().removeControl(cameraController);
            Vector3f eye = player.getNode().getLocalTranslation().add(0, 3, 0);
            cam.setLocation(eye);
            cam.lookAt(eye.add(environment.getSun().getDirection().negate()), Vector3f.UNIT_Y);
        } else if ("tcam".equals(showcase)) {
            cameraController.toggle();
        } else if ("profile".equals(showcase) || "helmet".equals(showcase)) {
            cameraController.setProfile("helmet".equals(showcase));
        } else if (showcase != null) {
            String[] p = showcase.split(",");
            cameraController.setShowcase(Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]));
        }
    }

    private int perfFrames;
    private float perfTime;

    private boolean shotSwitched;

    private void autoShots(float tpf) {
        elapsed += tpf;
        // RACINGSIM_SHOT_SWITCH=<s>: change the track on the menu once, <s> seconds in (TRACK row)
        String switchAt = System.getenv("RACINGSIM_SHOT_SWITCH");
        if (switchAt != null && !shotSwitched && state == State.MENU && elapsed > Float.parseFloat(switchAt)) {
            shotSwitched = true;
            while (!menu.onTrackRow()) menu.moveRow(1);
            changeSetting(1);
            return;
        }
        if ("race".equals(System.getenv("RACINGSIM_SHOT_CONFIRM")) && !confirmOpen && state == State.RACE && elapsed > 4f
                && elapsed < 4.5f) {
            openConfirm();
        }
        perfFrames++;
        perfTime += tpf;
        if (perfTime > 2f) {
            System.out.printf("PERF t=%.1f fps=%.1f state=%s mouseSteer=%b rpm=%.0f | %s%n", elapsed, perfFrames / perfTime, state,
                    playerInput.isMouseSteering(),
                    director.getPlayer().getPhysics().getEngineRpm(), audio.debugState());
            perfFrames = 0;
            perfTime = 0f;
        }
        String custom = System.getenv("RACINGSIM_SHOT_TIMES");   // e.g. "1.5,3"
        if (custom == null) custom = "6";
        String[] parts = custom.split(",");
        if (shotIndex < parts.length && elapsed > Float.parseFloat(parts[shotIndex].trim())) {
            screenshots.takeScreenshot();
            shotIndex++;
        } else if (shotIndex >= parts.length && elapsed > Float.parseFloat(parts[parts.length - 1].trim()) + 1f) {
            stop();
        }
    }
}
