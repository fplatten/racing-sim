package com.racingsim.app;

import com.jme3.system.AppSettings;

/** Entry point: {@code ./gradlew run}. */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        nativeImageJavaHome();
        RaceApp app = new RaceApp();
        AppSettings settings = new AppSettings(true);
        settings.setTitle("Oval Racing Sim POC - jMonkeyEngine");
        settings.setResolution(1600, 900);
        settings.setSamples(0);          // anti-aliasing is done by FXAA in the post stack
        settings.setGammaCorrection(true);
        settings.setVSync(true);
        settings.setFrameRate(-1);
        settings.setRenderer(AppSettings.LWJGL_OPENGL32);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
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
