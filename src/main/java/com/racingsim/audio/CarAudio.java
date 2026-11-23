package com.racingsim.audio;

import com.jme3.audio.AudioBuffer;
import com.jme3.audio.AudioData;
import com.jme3.audio.AudioKey;
import com.jme3.audio.AudioNode;
import com.jme3.audio.AudioSource;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import com.racingsim.vehicle.Car;
import com.racingsim.vehicle.VehiclePhysics;

import java.util.ArrayList;
import java.util.List;

/**
 * Engine, tire-squeal and wind sound for the race.
 * <ul>
 *   <li>Player: two engine loops (recorded at 4,500 and 9,500 rpm) pitch-shifted to the real
 *       engine rpm and cross-faded, louder on throttle; tire squeal driven by slip beyond the
 *       grip peak; wind rush rising with speed.</li>
 *   <li>AI cars: a positional engine each, attached to the car, with a manually computed
 *       Doppler shift relative to the listener (the camera follows the player's car). They use
 *       darker exterior loops ({@link SoundSynth#exteriorEngine}) in several voicings plus a small
 *       per-car detune, so a pack of identical waveforms does not beat / flange into a warble;
 *       throttle-driven volume and Doppler are smoothed.</li>
 * </ul>
 */
public final class CarAudio {

    private static final float LOW_RPM = 4500f, HIGH_RPM = 9500f;
    private static final float SPEED_OF_SOUND = 343f;
    private static final float MASTER = 0.9f;

    private final Car player;
    private final AudioNode engineLow, engineHigh, squeal, wind;
    private final List<AudioNode> aiEngines = new ArrayList<>();
    private final List<Car> aiCars = new ArrayList<>();
    private final List<AudioNode> all = new ArrayList<>();
    private final Camera cam;
    private boolean playing;
    private float throttleSmooth;
    private static final int AI_VOICES = 6;
    private final AudioBuffer[] aiBuffers = new AudioBuffer[AI_VOICES];
    private final float[][] aiVoiceSamples = new float[AI_VOICES][];
    private final java.util.Map<Car, float[]> aiState = new java.util.HashMap<>();   // throttle, doppler, detune
    private final java.util.Map<Car, AudioNode> aiEngineCache = new java.util.HashMap<>();
    // raw samples of every loop, kept for the offline mixdown used when recording a movie
    private final java.util.Map<AudioNode, float[]> samples = new java.util.HashMap<>();
    private final java.util.Map<AudioNode, double[]> mixPos = new java.util.HashMap<>();

    public CarAudio(Node rootNode, Camera cam, Car player) {
        this.cam = cam;
        this.player = player;
        float[] lowS = SoundSynth.engine(LOW_RPM, 0.2f, 11);
        float[] highS = SoundSynth.engine(HIGH_RPM, 0.85f, 12);
        float[] squealS = SoundSynth.squeal(5), windS = SoundSynth.wind(9);
        for (int v = 0; v < AI_VOICES; v++) {
            aiVoiceSamples[v] = SoundSynth.exteriorEngine(HIGH_RPM, 21 + 13L * v);
            aiBuffers[v] = SoundSynth.toBuffer(aiVoiceSamples[v]);
        }
        engineLow = loop("EngineLow", SoundSynth.toBuffer(lowS), false, rootNode);
        engineHigh = loop("EngineHigh", SoundSynth.toBuffer(highS), false, rootNode);
        squeal = loop("TireSqueal", SoundSynth.toBuffer(squealS), false, rootNode);
        wind = loop("Wind", SoundSynth.toBuffer(windS), false, rootNode);
        samples.put(engineLow, lowS);
        samples.put(engineHigh, highS);
        samples.put(squeal, squealS);
        samples.put(wind, windS);
    }

    /**
     * Sets the AI cars of the current field: each gets a positional engine (created on first use
     * and reused); engines of cars that left the field are stopped.
     */
    public void setField(List<Car> aiField) {
        for (int i = 0; i < aiCars.size(); i++) {
            if (!aiField.contains(aiCars.get(i))) {
                AudioNode n = aiEngines.get(i);
                if (n.getStatus() != AudioSource.Status.Stopped) n.stop();
                all.remove(n);
            }
        }
        aiCars.clear();
        aiEngines.clear();
        for (Car c : aiField) {
            AudioNode n = aiEngineCache.get(c);
            if (n == null) {
                int voice = aiEngineCache.size() % AI_VOICES;
                n = loop("Engine-" + c.getName(), aiBuffers[voice], true, c.getNode());
                samples.put(n, aiVoiceSamples[voice]);
                // detune +-2.5 %: cars at the same rpm drift apart instead of beating together
                float detune = 1f + 0.05f * (((aiEngineCache.size() * 0.618034f) % 1f) - 0.5f);
                aiState.put(c, new float[]{0.5f, 1f, detune});
                n.setRefDistance(9f);
                n.setMaxDistance(2000f);
                aiEngineCache.put(c, n);
            } else if (!all.contains(n)) {
                all.add(n);
            }
            if (playing && n.getStatus() != AudioSource.Status.Playing) n.play();
            aiCars.add(c);
            aiEngines.add(n);
        }
    }

    private AudioNode loop(String name, AudioData data, boolean positional, Node parent) {
        AudioNode n = new AudioNode(data, new AudioKey(name));
        n.setName(name);
        n.setLooping(true);
        n.setPositional(positional);
        n.setVolume(0f);
        parent.attachChild(n);
        all.add(n);
        return n;
    }

    /** Starts / pauses every sound (menu = silent). */
    public void setActive(boolean active) {
        if (active == playing) return;
        playing = active;
        for (AudioNode n : all) {
            if (active) n.play();
            else if (n.getStatus() == AudioSource.Status.Playing) n.pause();
        }
    }

    public void update(float tpf) {
        if (!playing) return;
        VehiclePhysics p = player.getPhysics();

        // ---- player engine ----
        float rpm = Math.max(p.getEngineRpm(), 2500f);
        float throttle = p.getGearbox().isShifting() ? 0f : player.getInput().throttle;
        if (p.getGearbox().getGear() == 0) throttle = player.getInput().brake;
        throttleSmooth += (throttle - throttleSmooth) * Math.min(1f, tpf / 0.08f);
        float load = 0.42f + 0.58f * throttleSmooth;
        float xf = smooth((rpm - 6000f) / 2500f);
        engineLow.setPitch(pitch(rpm / LOW_RPM));
        engineHigh.setPitch(pitch(rpm / HIGH_RPM));
        engineLow.setVolume(MASTER * load * (1f - xf) * 0.9f);
        engineHigh.setVolume(MASTER * load * xf);

        // ---- tires ----
        float sq = p.getSqueal();
        squeal.setVolume(MASTER * 0.85f * sq);
        squeal.setPitch(pitch(0.92f + 0.25f * sq));

        // ---- wind ----
        float v = p.getSpeed();
        float w = FastMath.clamp(v / 100f, 0f, 1.2f);
        wind.setVolume(MASTER * 0.45f * w * w);
        wind.setPitch(pitch(0.8f + 0.35f * w));

        // ---- AI engines with Doppler shift relative to the listener ----
        Vector3f listenerPos = cam.getLocation();
        Vector3f listenerVel = player.getBody().getLinearVelocity();
        for (int i = 0; i < aiCars.size(); i++) {
            Car c = aiCars.get(i);
            AudioNode n = aiEngines.get(i);
            VehiclePhysics cp = c.getPhysics();
            Vector3f toSource = c.getNode().getWorldTranslation().subtract(listenerPos);
            float dist = toSource.length();
            float doppler = 1f;
            if (dist > 0.5f) {
                toSource.divideLocal(dist);
                float vs = c.getBody().getLinearVelocity().dot(toSource);   // + = moving away
                float vl = listenerVel.dot(toSource);                        // + = moving toward
                doppler = (SPEED_OF_SOUND + vl) / (SPEED_OF_SOUND + vs);
            }
            float[] st = aiState.get(c);
            st[0] += (c.getInput().throttle - st[0]) * Math.min(1f, tpf / 0.25f);     // smoothed throttle
            st[1] += (doppler - st[1]) * Math.min(1f, tpf / 0.12f);                   // smoothed Doppler
            float aiRpm = Math.max(cp.getEngineRpm(), 3000f);
            n.setPitch(pitch(aiRpm / HIGH_RPM * st[1] * st[2]));
            n.setVolume(MASTER * (0.6f + 0.4f * st[0]) * 0.75f);
        }
    }

    /**
     * Offline mixdown of what the player hears right now, for movie recording (the jME video
     * recorder captures no sound). Renders {@code left.length} samples at {@link SoundSynth#RATE}
     * with the current pitch / volume of every loop; AI engines get OpenAL's inverse-distance
     * attenuation and a stereo pan from the camera. Writes silence while the sound is paused.
     */
    public void mixdown(float[] left, float[] right) {
        java.util.Arrays.fill(left, 0f);
        java.util.Arrays.fill(right, 0f);
        if (!playing) return;
        for (AudioNode n : new AudioNode[]{engineLow, engineHigh, squeal, wind}) {
            mixSource(n, samples.get(n), 1f, 1f, left, right);
        }
        Vector3f listener = cam.getLocation();
        Vector3f camLeft = cam.getLeft();
        for (int i = 0; i < aiCars.size(); i++) {
            AudioNode n = aiEngines.get(i);
            Vector3f to = aiCars.get(i).getNode().getWorldTranslation().subtract(listener);
            float dist = to.length();
            float ref = n.getRefDistance();
            float d = FastMath.clamp(dist, ref, n.getMaxDistance());
            float gain = ref / (ref + (d - ref));                        // AL_INVERSE_DISTANCE_CLAMPED
            float pan = dist > 0.5f ? to.divideLocal(dist).dot(camLeft) : 0f;   // +1 = left
            mixSource(n, samples.get(n), gain * (1f + 0.5f * pan), gain * (1f - 0.5f * pan), left, right);
        }
    }

    private void mixSource(AudioNode n, float[] s, float gl, float gr, float[] left, float[] right) {
        double[] pos = mixPos.computeIfAbsent(n, k -> new double[]{(mixPos.size() * 7919L) % s.length});
        float vol = n.getVolume(), pitch = n.getPitch();
        double p = pos[0];
        int len = s.length;
        for (int i = 0; i < left.length; i++) {
            int a = (int) p;
            float f = (float) (p - a);
            float v = (s[a] * (1f - f) + s[(a + 1) % len] * f) * vol;
            left[i] += v * gl;
            right[i] += v * gr;
            p += pitch;
            if (p >= len) p -= len;
        }
        pos[0] = p;
    }

    /** One-line summary of the live audio state (diagnostics). */
    public String debugState() {
        return String.format("engineLow[%s vol %.2f pitch %.2f] engineHigh[%s vol %.2f pitch %.2f] squeal %.2f wind %.2f aiEngines %d",
                engineLow.getStatus(), engineLow.getVolume(), engineLow.getPitch(),
                engineHigh.getStatus(), engineHigh.getVolume(), engineHigh.getPitch(),
                squeal.getVolume(), wind.getVolume(), aiEngines.size());
    }

    private static float pitch(float p) {
        return FastMath.clamp(p, 0.5f, 2.0f);       // OpenAL / jME pitch range
    }

    private static float smooth(float x) {
        x = FastMath.clamp(x, 0f, 1f);
        return x * x * (3 - 2 * x);
    }
}
