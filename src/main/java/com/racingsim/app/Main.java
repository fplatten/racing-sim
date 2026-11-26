package com.racingsim.app;

import com.jme3.system.AppSettings;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;

/** Entry point: {@code ./gradlew run}. */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        nativeImageJavaHome();
        RaceApp app = new RaceApp();
        AppSettings settings = new AppSettings(true);
        settings.setTitle("Racing Sim POC - jMonkeyEngine");
        settings.setResolution(1600, 900);
        settings.setSamples(0);          // anti-aliasing is done by FXAA in the post stack
        settings.setGammaCorrection(true);
        settings.setVSync(true);
        settings.setFrameRate(-1);
        settings.setRenderer(AppSettings.LWJGL_OPENGL32);
        if (!soundDeviceWorks()) {
            // jME would stop with "no OpenAL context current": race in silence instead
            System.err.println("No sound device could be opened; the game runs without sound.");
            settings.setAudioRenderer(null);
        }
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    /**
     * Opens the default sound device and an OpenAL context on it the way jME's LwjglALC does, then
     * closes them again. False if any step fails (no device, or one Windows will not start, for
     * example a monitor's speakers while the monitor sleeps).
     */
    private static boolean soundDeviceWorks() {
        if (System.getProperty("racingsim.silent") != null) return false;
        long device = 0, context = 0;
        try {
            device = ALC10.alcOpenDevice((java.nio.ByteBuffer) null);
            if (device == 0) return false;
            ALC.createCapabilities(device);
            context = ALC10.alcCreateContext(device, (java.nio.IntBuffer) null);
            return context != 0 && ALC10.alcMakeContextCurrent(context);
        } catch (RuntimeException | LinkageError e) {
            return false;
        } finally {
            if (context != 0) {
                ALC10.alcMakeContextCurrent(0);
                ALC10.alcDestroyContext(context);
            }
            if (device != 0) ALC10.alcCloseDevice(device);
        }
    }

    /**
     * GraalVM native executable: there is no JDK install, so {@code java.home} is unset and AWT's
     * font setup (car numbers and tire lettering are drawn with AWT) fails. Point it at the
     * executable's folder, where the release ships {@code lib/fontconfig.bfc}.
     */
    private static void nativeImageJavaHome() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) return;
        if (System.getProperty("java.home") != null) return;
        String dir = ProcessHandle.current().info().command()
                .map(c -> java.nio.file.Path.of(c).toAbsolutePath().getParent().toString())
                .orElse(System.getProperty("user.dir"));
        System.setProperty("java.home", dir);
    }
}
