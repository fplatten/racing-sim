package com.racingsim.audio;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds a PCM sound track to an MJPEG AVI (as written by jME's VideoRecorderAppState): the JPEG
 * frames are copied into a new AVI with a second, interleaved audio stream (16-bit stereo).
 */
public final class AviAudioMuxer {

    private AviAudioMuxer() { }

    /**
     * @param avi        the video-only AVI; replaced by the version with sound
     * @param pcm        16-bit little-endian stereo samples
     * @param sampleRate audio sample rate (Hz)
     * @param fps        video frame rate
     */
    public static void mux(File avi, byte[] pcm, int sampleRate, int fps) throws IOException {
        List<long[]> frames = new ArrayList<>();      // {offset, size} of each JPEG
        int width, height;
        try (RandomAccessFile in = new RandomAccessFile(avi, "r")) {
            int[] wh = new int[2];
            scan(in, 12, in.length(), frames, wh);
            width = wh[0];
            height = wh[1];
        }
        if (frames.isEmpty()) throw new IOException("no video frames in " + avi);

        int n = frames.size();
        int samplesPerFrame = sampleRate / fps, audioBytesPerFrame = samplesPerFrame * 4;
        int maxJpeg = 0;
        for (long[] f : frames) maxJpeg = (int) Math.max(maxJpeg, f[1]);

        File tmp = new File(avi.getPath() + ".tmp");
        try (RandomAccessFile in = new RandomAccessFile(avi, "r");
             RandomAccessFile out = new RandomAccessFile(tmp, "rw")) {
            out.setLength(0);
            ByteBuffer h = le(2048);
            h.put(cc("RIFF")).putInt(0).put(cc("AVI "));
            // ---- header list ----
            h.put(cc("LIST")).putInt(4 + 64 + (12 + 64 + 48) + (12 + 64 + 26)).put(cc("hdrl"));
            h.put(cc("avih")).putInt(56)
                    .putInt(1_000_000 / fps).putInt(maxJpeg * fps + sampleRate * 4).putInt(0)
                    .putInt(0x10 | 0x100)                                   // HASINDEX | ISINTERLEAVED
                    .putInt(n).putInt(0).putInt(2).putInt(maxJpeg + audioBytesPerFrame)
                    .putInt(width).putInt(height).putInt(0).putInt(0).putInt(0).putInt(0);
            // video stream
            h.put(cc("LIST")).putInt(4 + 64 + 48).put(cc("strl"));
            h.put(cc("strh")).putInt(56).put(cc("vids")).put(cc("MJPG")).putInt(0).putShort((short) 0).putShort((short) 0)
                    .putInt(0).putInt(1).putInt(fps).putInt(0).putInt(n).putInt(maxJpeg).putInt(-1).putInt(0)
                    .putShort((short) 0).putShort((short) 0).putShort((short) width).putShort((short) height);
            h.put(cc("strf")).putInt(40).putInt(40).putInt(width).putInt(height).putShort((short) 1).putShort((short) 24)
                    .put(cc("MJPG")).putInt(width * height * 3).putInt(0).putInt(0).putInt(0).putInt(0);
            // audio stream
            h.put(cc("LIST")).putInt(4 + 64 + 26).put(cc("strl"));
            h.put(cc("strh")).putInt(56).put(cc("auds")).putInt(0).putInt(0).putShort((short) 0).putShort((short) 0)
                    .putInt(0).putInt(1).putInt(sampleRate).putInt(0).putInt(n * samplesPerFrame)
                    .putInt(audioBytesPerFrame).putInt(-1).putInt(4)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0);
            h.put(cc("strf")).putInt(18).putShort((short) 1).putShort((short) 2).putInt(sampleRate)
                    .putInt(sampleRate * 4).putShort((short) 4).putShort((short) 16).putShort((short) 0);
            // ---- movie data ----
            long moviSizePos = h.position() + 4;
            h.put(cc("LIST")).putInt(0).put(cc("movi"));
            long moviStart = h.position() - 4;
            write(out, h);

            ByteBuffer idx = le(n * 32);
            byte[] jpeg = new byte[maxJpeg];
            byte[] audio = new byte[audioBytesPerFrame];
            ByteBuffer ch = le(8);
            for (int i = 0; i < n; i++) {
                long[] f = frames.get(i);
                int size = (int) f[1];
                in.seek(f[0]);
                in.readFully(jpeg, 0, size);
                long pos = out.getFilePointer();
                ch.clear();
                ch.put(cc("00dc")).putInt(size);
                write(out, ch);
                out.write(jpeg, 0, size);
                if ((size & 1) != 0) out.write(0);
                idx.put(cc("00dc")).putInt(0x10).putInt((int) (pos - moviStart)).putInt(size);

                java.util.Arrays.fill(audio, (byte) 0);
                int from = i * audioBytesPerFrame;
                if (from < pcm.length) System.arraycopy(pcm, from, audio, 0, Math.min(audioBytesPerFrame, pcm.length - from));
                pos = out.getFilePointer();
                ch.clear();
                ch.put(cc("01wb")).putInt(audioBytesPerFrame);
                write(out, ch);
                out.write(audio);
                idx.put(cc("01wb")).putInt(0x10).putInt((int) (pos - moviStart)).putInt(audioBytesPerFrame);
            }
            long moviEnd = out.getFilePointer();
            ch.clear();
            ch.put(cc("idx1")).putInt(idx.position());
            write(out, ch);
            write(out, idx);
            long end = out.getFilePointer();
            patch(out, 4, (int) (end - 8));
            patch(out, moviSizePos, (int) (moviEnd - moviSizePos - 4));
        }
        Files.move(tmp.toPath(), avi.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Walks RIFF chunks, collecting JPEG frame chunks and the frame size from 'avih'. */
    private static void scan(RandomAccessFile in, long from, long to, List<long[]> frames, int[] wh) throws IOException {
        long p = from;
        byte[] id = new byte[4];
        while (p + 8 <= to) {
            in.seek(p);
            in.readFully(id);
            long size = Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));
            String s = new String(id, java.nio.charset.StandardCharsets.US_ASCII);
            if (s.equals("LIST")) {
                scan(in, p + 12, Math.min(to, p + 8 + size), frames, wh);
            } else if (s.equals("avih")) {
                in.seek(p + 8 + 32);
                wh[0] = Integer.reverseBytes(in.readInt());
                wh[1] = Integer.reverseBytes(in.readInt());
            } else if (s.endsWith("dc") || s.endsWith("db")) {
                if (size > 0) frames.add(new long[]{p + 8, size});
            }
            p += 8 + size + (size & 1);
        }
    }

    private static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] cc(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static void write(RandomAccessFile out, ByteBuffer b) throws IOException {
        out.write(b.array(), 0, b.position());
    }

    private static void patch(RandomAccessFile out, long pos, int value) throws IOException {
        out.seek(pos);
        out.writeInt(Integer.reverseBytes(value));
    }
}
