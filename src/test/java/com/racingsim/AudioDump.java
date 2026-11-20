package com.racingsim;

import com.racingsim.audio.SoundSynth;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/** Writes the synthesized loops as WAV files for listening / analysis: ./gradlew audioDump -Pout=dir */
public final class AudioDump {

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "build/audio");
        dir.toFile().mkdirs();
        write(dir.resolve("engine_low.wav"), SoundSynth.engine(4500f, 0.2f, 11), 3);
        write(dir.resolve("engine_high.wav"), SoundSynth.engine(9500f, 0.85f, 12), 3);
        write(dir.resolve("squeal.wav"), SoundSynth.squeal(5), 2);
        write(dir.resolve("wind.wav"), SoundSynth.wind(9), 1);
        System.out.println("wrote " + dir.toAbsolutePath());
    }

    /** Writes the loop repeated {@code repeats} times so seams can be heard. */
    private static void write(Path file, float[] samples, int repeats) throws IOException {
        int n = samples.length * repeats;
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file.toFile()))) {
            out.writeBytes("RIFF");
            out.writeInt(Integer.reverseBytes(36 + n * 2));
            out.writeBytes("WAVEfmt ");
            out.writeInt(Integer.reverseBytes(16));
            out.writeShort(Short.reverseBytes((short) 1));
            out.writeShort(Short.reverseBytes((short) 1));
            out.writeInt(Integer.reverseBytes(SoundSynth.RATE));
            out.writeInt(Integer.reverseBytes(SoundSynth.RATE * 2));
            out.writeShort(Short.reverseBytes((short) 2));
            out.writeShort(Short.reverseBytes((short) 16));
            out.writeBytes("data");
            out.writeInt(Integer.reverseBytes(n * 2));
            for (int r = 0; r < repeats; r++) {
                for (float s : samples) {
                    out.writeShort(Short.reverseBytes((short) Math.round(Math.max(-1f, Math.min(1f, s)) * 32000)));
                }
            }
        }
    }
}
