package com.racingsim.bot;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The game side of the bot protocol (bots/PROTOCOL.md): listens on 127.0.0.1 only and keeps the
 * bots that have said hello. Turned on with {@code -Dbots=<port>} (gradle {@code -Pbots=4510}) or
 * the environment variable {@code RACINGSIM_BOTS=<port>}.
 */
public final class BotServer {

    public static final int DEFAULT_PORT = 4510;
    public static final int PROTOCOL = 1;

    private final ServerSocket server;
    private final List<BotConnection> bots = new CopyOnWriteArrayList<>();
    private int nextId = 1;

    public BotServer(int port) throws IOException {
        server = new ServerSocket(port, 16, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::acceptLoop, "bot-server");
        t.setDaemon(true);
        t.start();
        System.out.println("Bot server listening on 127.0.0.1:" + server.getLocalPort());
    }

    /** The port asked for on the command line / environment, or -1 for no bot server. */
    public static int configuredPort() {
        String v = System.getProperty("bots");
        if (v == null || v.isBlank()) v = System.getenv("RACINGSIM_BOTS");
        if (v == null || v.isBlank()) return -1;
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on")) return DEFAULT_PORT;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    public int port() { return server.getLocalPort(); }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                BotConnection c = new BotConnection(s, nextId++);
                bots.add(c);
            } catch (IOException e) {
                if (!server.isClosed()) System.err.println("Bot server: " + e.getMessage());
            }
        }
    }

    /** Connected bots that have said hello, in the order they connected. */
    public List<BotConnection> ready() {
        bots.removeIf(b -> !b.isOpen());
        List<BotConnection> r = new ArrayList<>();
        for (BotConnection b : bots) if (b.helloReceived()) r.add(b);
        return r;
    }

    /** Waits (up to {@code timeoutMs}) until {@code count} bots are ready. */
    public List<BotConnection> awaitBots(int count, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (ready().size() < count && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ready();
    }

    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        for (BotConnection b : bots) b.close();
    }
}
