package com.racingsim.app;

import com.jme3.system.AppSettings;

/** Entry point: {@code ./gradlew run}. */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
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
}
