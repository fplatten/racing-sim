package com.racingsim.audio;

import com.jme3.audio.AudioBuffer;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Procedural sound synthesis: seamless mono loops (16-bit, 44.1 kHz) for the engine, the
 * tire squeal and wind noise, generated at start-up (no audio files to ship).
 */
public final class SoundSynth {

    public static final int RATE = 44100;

    private SoundSynth() { }

    /**
     * Naturally-aspirated racing V8 at a fixed rpm: per-cylinder exhaust pulses (damped
     * resonances, slightly uneven between cylinders, repeating every 720 degrees of crank
     * rotation), crank-order harmonics for the mechanical whine, a little combustion noise and
     * soft saturation. The loop holds a whole number of engine cycles so it repeats seamlessly.
     *
     * @param rpm        engine speed the loop is recorded at
     * @param brightness 0 = deep / boomy, 1 = screaming high-rpm character
     */
    public static float[] engine(float rpm, float brightness, long seed) {
        return engine(rpm, brightness, seed, 0.4);
    }

    /**
     * An opponent's engine heard from outside the car: a deep, rough V8 rumble rather than the
     * in-car scream. Built firing by firing over a loop of whole engine cycles: every exhaust pulse
     * (a damped low body resonance plus a little rasp) gets its own small timing, strength and
     * resonance variation, as real combustion does, on top of a fixed per-cylinder unevenness that
     * gives the once-per-cycle lope. Because the loop is not perfectly periodic, several cars at
     * nearly the same rpm blend into a roar instead of beating against each other (a warble).
     * Low exhaust roar fills in underneath; the top end is rolled off as distance and bodywork do.
     */
    public static float[] exteriorEngine(float rpm, long seed) {
        Random rnd = new Random(seed * 17 + 3);
        int spc = (int) Math.round(RATE * 2.0 / (rpm / 60.0));          // samples per 720 degrees
        int cycles = Math.max(1, (int) Math.round(1.5 * RATE / spc));
        int n = spc * cycles;
        int cylinders = 8, firings = cylinders * cycles;
        double interval = (double) n / firings;
        double[] cylAmp = new double[cylinders], cylRes = new double[cylinders];
        for (int c = 0; c < cylinders; c++) {
            cylAmp[c] = 1.0 + 0.7 * (rnd.nextDouble() - 0.5);
            cylRes[c] = 1.0 + 0.10 * (rnd.nextDouble() - 0.5);
        }
        double body = 150 + 40 * rnd.nextDouble();                       // exhaust body resonance (Hz)
        double rasp = 700 + 200 * rnd.nextDouble();
        int len = (int) (0.03 * RATE);
        float[] e = new float[n];
        for (int j = 0; j < firings; j++) {
            int c = j % cylinders;
            double start = j * interval + (rnd.nextDouble() - 0.5) * 0.16 * interval;
            double a = cylAmp[c] * (0.9 + 0.2 * rnd.nextDouble());
            double fb = body * cylRes[c] * (1 + 0.08 * (rnd.nextDouble() - 0.5));
            double fr = rasp * (1 + 0.15 * (rnd.nextDouble() - 0.5));
            int i0 = (int) Math.ceil(start);
            for (int k = 0; k < len; k++) {
                double t = (i0 + k - start) / RATE;
                double v = Math.exp(-t / 0.0050) * Math.sin(2 * Math.PI * fb * t)
                        + 0.22 * Math.exp(-t / 0.0011) * Math.sin(2 * Math.PI * fr * t);
                e[Math.floorMod(i0 + k, n)] += (float) (a * v);
            }
        }
        lowPass(e, 2000);
        lowPass(e, 2000);
        normalize(e, 1f);
        float[] roar = loopNoise(n, rnd);
        lowPass(roar, 260);
        lowPass(roar, 260);
        normalize(roar, 1f);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = 0.9f * e[i] + 0.10f * roar[i];
        saturate(out, 1.5f);
        highPass(out, 30);
        normalize(out, 0.9f);
        return out;
    }

    private static float[] engine(float rpm, float brightness, long seed, double unevenness) {
        Random rnd = new Random(seed);
        double crankHz = rpm / 60.0;
        int samplesPerCycle = (int) Math.round(RATE * 2.0 / crankHz);   // 720 degrees
        int cycles = Math.max(1, (int) Math.round(1.2 * RATE / samplesPerCycle));
        int n = samplesPerCycle * cycles;
        double cycleHz = (double) RATE / samplesPerCycle;               // exact (loop-safe)
        double crank = cycleHz * 2.0;
        float[] out = new float[n];

        // per-cylinder character, identical every cycle -> periodic
        int cylinders = 8;
        double[] amp = new double[cylinders], offset = new double[cylinders], res = new double[cylinders];
        for (int c = 0; c < cylinders; c++) {
            amp[c] = 1.0 + unevenness * (rnd.nextDouble() - 0.5);
            offset[c] = (rnd.nextDouble() - 0.5) * 0.04;                  // fraction of a firing interval
            res[c] = 1.0 + (rnd.nextDouble() - 0.5) * 0.12;
        }
        double resLow = 160 + 260 * brightness;      // exhaust body resonance (Hz)
        double resHigh = 900 + 1000 * brightness;    // exhaust rasp
        double decayLow = 0.0045 - 0.002 * brightness, decayHigh = 0.0012;
        float[] pulse = new float[samplesPerCycle];
        for (int c = 0; c < cylinders; c++) {
            double start = (c + offset[c]) / cylinders * samplesPerCycle;
            for (int k = 0; k < samplesPerCycle; k++) {
                // distance since this cylinder fired, wrapped around the cycle
                double t = ((k - start) % samplesPerCycle + samplesPerCycle) % samplesPerCycle / RATE;
                double body = Math.exp(-t / decayLow) * Math.sin(2 * Math.PI * resLow * res[c] * t);
                double rasp = Math.exp(-t / decayHigh) * Math.sin(2 * Math.PI * resHigh * res[c] * t);
                pulse[k] += (float) (amp[c] * (body + (0.25 + 0.2 * brightness) * rasp));
            }
        }
        // combustion noise burst shapes, also periodic per cycle
        float[] burst = new float[samplesPerCycle];
        for (int c = 0; c < cylinders; c++) {
            double start = (double) c / cylinders * samplesPerCycle;
            Random nr = new Random(seed * 31 + c);
            for (int k = 0; k < samplesPerCycle; k++) {
                double t = ((k - start) % samplesPerCycle + samplesPerCycle) % samplesPerCycle / RATE;
                burst[k] += (float) (Math.exp(-t / 0.0008) * (nr.nextDouble() * 2 - 1));
            }
        }
        for (int i = 0; i < n; i++) {
            int k = i % samplesPerCycle;
            double t = (double) i / RATE;
            double mech = 0;
            for (int h = 1; h <= 24; h++) {                                // crank orders
                double a = (h % 4 == 0 ? 0.5 : 0.12) / h;                  // firing orders dominate
                if (h == 2) a = 0.25;
                mech += a * Math.sin(2 * Math.PI * crank * h * t + h * 0.7);
            }
            double whine = 0.04 * brightness * Math.sin(2 * Math.PI * crank * 32 * t);   // gear / valve train
            out[i] = (float) (0.75 * pulse[k] + 0.25 * mech + 0.12 * burst[k] + whine);
        }
        saturate(out, 1.6f + brightness);
        highPass(out, 40);
        normalize(out, 0.9f);
        return out;
    }

    /** Tire squeal: wobbling tonal squeal (stick-slip) plus band-passed scrub noise. */
    public static float[] squeal(long seed) {
        Random rnd = new Random(seed);
        int n = (int) (RATE * 1.5);
        double dur = (double) n / RATE;
        float[] out = new float[n];
        // loop-safe LFOs: whole numbers of cycles per loop
        double wobbleHz = Math.round(5.0 * dur) / dur, flutterHz = Math.round(43.0 * dur) / dur;
        double base = 870;
        double phase1 = 0, phase2 = 0, phase3 = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double wobble = 1 + 0.035 * Math.sin(2 * Math.PI * wobbleHz * t) + 0.012 * Math.sin(2 * Math.PI * wobbleHz * 3 * t + 1);
            double f = base * wobble;
            phase1 += 2 * Math.PI * f / RATE;
            phase2 += 2 * Math.PI * f * 2.02 / RATE;
            phase3 += 2 * Math.PI * f * 3.1 / RATE;
            double flutter = 0.75 + 0.25 * Math.sin(2 * Math.PI * flutterHz * t);
            out[i] = (float) (flutter * (Math.sin(phase1) + 0.45 * Math.sin(phase2) + 0.2 * Math.sin(phase3)));
        }
        float[] noise = loopNoise(n, rnd);
        bandPass(noise, 1800, 0.8);
        normalize(noise, 1f);
        for (int i = 0; i < n; i++) out[i] = 0.7f * out[i] + 0.45f * noise[i];
        saturate(out, 1.3f);
        normalize(out, 0.85f);
        return out;
    }

    /** Wind / road rush: low-passed noise with a gentle broadband component. */
    public static float[] wind(long seed) {
        Random rnd = new Random(seed);
        int n = RATE * 2;
        float[] low = loopNoise(n, rnd);
        lowPass(low, 380);
        lowPass(low, 380);
        normalize(low, 1f);
        float[] hiss = loopNoise(n, rnd);
        bandPass(hiss, 2500, 0.5);
        normalize(hiss, 1f);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = 0.8f * low[i] + 0.25f * hiss[i];
        normalize(out, 0.8f);
        return out;
    }

    // ------------------------------------------------------------------ helpers
    /** White noise whose end cross-fades into its start (seamless when looped). */
    private static float[] loopNoise(int n, Random rnd) {
        int fade = RATE / 10;
        float[] raw = new float[n + fade];
        for (int i = 0; i < raw.length; i++) raw[i] = (float) (rnd.nextDouble() * 2 - 1);
        float[] out = new float[n];
        System.arraycopy(raw, 0, out, 0, n);
        for (int i = 0; i < fade; i++) {
            float w = (float) i / fade;
            out[i] = raw[i] * w + raw[n + i] * (1 - w);
        }
        return out;
    }

    private static void saturate(float[] x, float drive) {
        float peak = peak(x);
        if (peak == 0) return;
        for (int i = 0; i < x.length; i++) x[i] = (float) Math.tanh(drive * x[i] / peak);
    }

    private static float peak(float[] x) {
        float p = 0;
        for (float v : x) p = Math.max(p, Math.abs(v));
        return p;
    }

    private static void normalize(float[] x, float level) {
        float p = peak(x);
        if (p == 0) return;
        float k = level / p;
        for (int i = 0; i < x.length; i++) x[i] *= k;
    }

    /** One-pole low pass, run twice around the loop so the filter state is seamless. */
    private static void lowPass(float[] x, double cutoff) {
        double a = Math.exp(-2 * Math.PI * cutoff / RATE);
        double y = 0;
        float[] in = x.clone();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < in.length; i++) {
                y = (1 - a) * in[i] + a * y;
                if (pass == 1) x[i] = (float) y;
            }
        }
    }

    private static void highPass(float[] x, double cutoff) {
        float[] low = x.clone();
        lowPass(low, cutoff);
        for (int i = 0; i < x.length; i++) x[i] -= low[i];
    }

    /** RBJ band-pass biquad, also primed by a first pass around the loop. */
    private static void bandPass(float[] x, double center, double q) {
        double w0 = 2 * Math.PI * center / RATE, alpha = Math.sin(w0) / (2 * q);
        double b0 = alpha, b2 = -alpha, a0 = 1 + alpha, a1 = -2 * Math.cos(w0), a2 = 1 - alpha;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        float[] in = x.clone();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < in.length; i++) {
                double y = (b0 * in[i] + b2 * x2 - a1 * y1 - a2 * y2) / a0;
                x2 = x1;
                x1 = in[i];
                y2 = y1;
                y1 = y;
                if (pass == 1) x[i] = (float) y;
            }
        }
    }

    /** Wraps samples into a jME audio buffer (mono, 16-bit). */
    public static AudioBuffer toBuffer(float[] samples) {
        ByteBuffer data = BufferUtils.createByteBuffer(samples.length * 2);
        for (float s : samples) {
            data.putShort((short) Math.round(Math.max(-1f, Math.min(1f, s)) * 32000));
        }
        data.flip();
        AudioBuffer buffer = new AudioBuffer();
        buffer.setupFormat(1, 16, RATE);
        buffer.updateData(data);
        return buffer;
    }
}
