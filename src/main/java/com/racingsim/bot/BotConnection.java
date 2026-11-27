package com.racingsim.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * One connected bot: a reader thread parses its lines (JSON), keeps the newest {@code control} for
 * the physics thread to pick up, and never lets anything the bot sends reach the physics unchecked.
 */
public final class BotConnection {

    /** Longest line accepted from a bot (bytes); longer ones close the connection. */
    static final int MAX_LINE = 256 * 1024;

    /** A bot's driving command, already clamped. Steering here is the game's: + = left. */
    public record Control(long tick, float steer, float throttle, float brake, int shift, List<Object> debug) {}

    private final Socket socket;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private final Object controlLock = new Object();
    private volatile boolean open = true;
    private volatile boolean helloReceived;
    private volatile String name = "Bot";
    private volatile String color1, color2;
    private volatile Control latest;
    private volatile Map<String, Object> resetRequest;
    private volatile long lastMessageNanos = System.nanoTime();
    private volatile int protocolErrors;
    private final int id;

    BotConnection(Socket socket, int id) throws IOException {
        this.socket = socket;
        this.id = id;
        socket.setTcpNoDelay(true);
        this.out = socket.getOutputStream();
        Thread t = new Thread(this::readLoop, "bot-" + id);
        t.setDaemon(true);
        t.start();
    }

    public int id() { return id; }
    public String name() { return name; }
    public String color1() { return color1; }
    public String color2() { return color2; }
    public boolean isOpen() { return open; }
    public boolean helloReceived() { return helloReceived; }
    public Control latest() { return latest; }
    /** Seconds since the bot last sent anything. */
    public float silentFor() { return (System.nanoTime() - lastMessageNanos) / 1e9f; }

    /** Training only: a pending {@code reset} request (taken: cleared). */
    public Map<String, Object> takeReset() {
        Map<String, Object> r = resetRequest;
        resetRequest = null;
        return r;
    }

    /** Lockstep: waits up to {@code timeoutMs} for a control of at least {@code tick} (or a training reset). */
    public Control awaitControl(long tick, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (controlLock) {
            while (open && resetRequest == null && (latest == null || latest.tick() < tick)) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    controlLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return latest;
        }
    }

    /** Lockstep (training): waits for a {@code reset} or a control. */
    public void awaitAnything(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (controlLock) {
            while (open && resetRequest == null) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    controlLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void send(String line) {
        if (!open) return;
        byte[] b = (line + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            try {
                out.write(b);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }
    }

    public void close() {
        open = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        synchronized (controlLock) {
            controlLock.notifyAll();
        }
    }

    private void readLoop() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder();
            int c;
            while (open && (c = in.read()) >= 0) {
                if (c != '\n') {
                    if (line.length() >= MAX_LINE) {
                        System.err.println("Bot " + name + ": line longer than " + MAX_LINE + " bytes, disconnecting");
                        break;
                    }
                    line.append((char) c);
                    continue;
                }
                String text = line.toString().trim();
                line.setLength(0);
                if (!text.isEmpty()) handle(text);
            }
        } catch (IOException ignored) {
            // the bot went away
        } finally {
            close();
        }
    }

    @SuppressWarnings("unchecked")
    private void handle(String text) {
        lastMessageNanos = System.nanoTime();
        Map<String, Object> m;
        try {
            Object v = Json.parse(text);
            if (!(v instanceof Map)) throw new Json.ParseException("not an object", 0);
            m = (Map<String, Object>) v;
        } catch (RuntimeException e) {
            if (protocolErrors++ < 5) System.err.println("Bot " + name + ": bad message (" + e.getMessage() + ")");
            return;
        }
        switch (Json.str(m, "type", "")) {
            case "hello" -> {
                String n = Json.str(m, "name", "Bot").replaceAll("[^\\p{L}\\p{N} _.\\-]", "").trim();
                name = n.isEmpty() ? "Bot" : n.substring(0, Math.min(12, n.length()));
                color1 = Json.str(m, "color1", null);
                color2 = Json.str(m, "color2", null);
                helloReceived = true;
            }
            case "control" -> {
                long tick = (long) Json.num(m, "tick", -1);
                Control prev = latest;
                // a NaN / missing value holds the previous one; everything is clamped
                float steer = clamp(Json.num(m, "steer", prev != null ? -prev.steer() : 0), -1, 1);
                float thr = clamp(Json.num(m, "throttle", prev != null ? prev.throttle() : 0), 0, 1);
                float brk = clamp(Json.num(m, "brake", prev != null ? prev.brake() : 0), 0, 1);
                int shift = (int) Math.signum(Json.num(m, "shift", 0));
                List<Object> debug = null;
                if (m.get("debug") instanceof Map<?, ?> dbg && dbg.get("lines") instanceof List<?> l) debug = (List<Object>) l;
                synchronized (controlLock) {
                    latest = new Control(tick, -steer, thr, brk, shift, debug);
                    controlLock.notifyAll();
                }
            }
            case "reset" -> {
                synchronized (controlLock) {
                    resetRequest = m;
                    controlLock.notifyAll();
                }
            }
            default -> {
                if (protocolErrors++ < 5) System.err.println("Bot " + name + ": unknown message type");
            }
        }
    }

    private static float clamp(double v, float lo, float hi) {
        if (Double.isNaN(v)) return 0f;
        return (float) Math.max(lo, Math.min(hi, v));
    }
}
