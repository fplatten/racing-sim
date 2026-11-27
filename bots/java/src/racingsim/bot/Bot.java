package racingsim.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Racing Sim bot SDK for Java (no dependencies). See bots/PROTOCOL.md.
 * <pre>
 *   public class MyBot extends Bot {
 *       public MyBot() { super("MyBot"); }
 *       public Control onTick(State s) { return new Control(0, 1, 0); }
 *       public static void main(String[] a) { new MyBot().run(a); }
 *   }
 * </pre>
 */
public abstract class Bot {

    public static final int PROTOCOL = 1;
    public static final int DEFAULT_PORT = 4510;

    protected final String name;
    protected String color1, color2;
    protected Track track;
    protected Map<String, Object> welcome;

    protected Bot(String name) {
        this.name = name;
    }

    /** Every control tick (30 a second by default). */
    public abstract Control onTick(State state);

    public void onWelcome(Map<String, Object> welcome) {}
    public void onTrack(Track track) {}
    public void onEvent(Map<String, Object> event) {}
    public void onFinish(Map<String, Object> result) {}

    /** Connects (--port N, else RACINGSIM_BOTS, else 4510) and drives until the race ends. */
    public void run(String[] args) {
        int port = DEFAULT_PORT;
        String env = System.getenv("RACINGSIM_BOTS");
        if (env != null && env.matches("\\d+")) port = Integer.parseInt(env);
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) port = Integer.parseInt(args[i + 1]);
            else if (args[i].startsWith("--port=")) port = Integer.parseInt(args[i].substring(7));
        }
        run("127.0.0.1", port);
    }

    @SuppressWarnings("unchecked")
    public void run(String host, int port) {
        Socket socket = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (socket == null) {
            try {
                socket = new Socket(host, port);
            } catch (IOException e) {
                if (System.currentTimeMillis() > deadline) throw new IllegalStateException(name + ": no game on " + host + ":" + port);
                sleep(500);
            }
        }
        try (Socket s = socket) {
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            Json.Out hello = new Json.Out().obj().str("type", "hello").num("protocol", PROTOCOL).str("name", name);
            if (color1 != null) hello.str("color1", color1);
            if (color2 != null) hello.str("color2", color2);
            send(out, hello.endObj().toString());
            System.out.println(name + ": connected to " + host + ":" + port);
            String line;
            while ((line = in.readLine()) != null) {
                Map<String, Object> m = (Map<String, Object>) Json.parse(line);
                switch (Json.str(m, "type", "")) {
                    case "state" -> {
                        State st = new State(m);
                        Control c = onTick(st);
                        if (c == null) c = new Control(0, 0, 0);
                        Json.Out o = new Json.Out().obj().str("type", "control").num("tick", st.tick)
                                .num("steer", c.steer).num("throttle", c.throttle).num("brake", c.brake).num("shift", c.shift);
                        if (!c.debug.isEmpty()) {
                            o.key("debug").obj().key("lines").arr();
                            for (Control.Line l : c.debug) o.arr().num(l.s1()).num(l.d1()).num(l.s2()).num(l.d2()).str(l.color()).endArr();
                            o.endArr().endObj();
                        }
                        send(out, o.endObj().toString());
                    }
                    case "welcome" -> {
                        welcome = m;
                        onWelcome(m);
                    }
                    case "track" -> {
                        track = new Track(m);
                        onTrack(track);
                    }
                    case "event" -> onEvent(m);
                    case "bye" -> {
                        onFinish(m);
                        return;
                    }
                    default -> { }
                }
            }
        } catch (IOException e) {
            System.out.println(name + ": the game closed the connection");
        }
    }

    private static void send(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A driving command: steer -1 (left) .. 1 (right), throttle and brake 0..1, shift -1/0/+1 (manual gearbox only). */
    public static final class Control {
        public double steer, throttle, brake;
        public int shift;
        public final List<Line> debug = new ArrayList<>();

        public Control(double steer, double throttle, double brake) {
            this.steer = steer;
            this.throttle = throttle;
            this.brake = brake;
        }

        /** A line drawn over the track in practice, from (s1, d1) to (s2, d2), color "#rrggbb". */
        public record Line(double s1, double d1, double s2, double d2, String color) {}

        public Control line(double s1, double d1, double s2, double d2, String color) {
            debug.add(new Line(s1, d1, s2, d2, color));
            return this;
        }
    }

    /** The {@code state} message, unpacked. */
    @SuppressWarnings("unchecked")
    public static final class State {
        public final long tick;
        public final double t;
        public final Map<String, Object> car;
        /** [distance, curvature, minD, maxD] at +10 .. +300 m. */
        public final double[][] ahead;
        public final double[] rays;
        public final List<Opponent> opponents = new ArrayList<>();
        public final String phase;
        public final Map<String, Object> raw;

        State(Map<String, Object> m) {
            raw = m;
            tick = (long) Json.num(m, "tick", 0);
            t = Json.num(m, "t", 0);
            car = (Map<String, Object>) m.get("car");
            List<Object> a = (List<Object>) m.get("ahead");
            ahead = new double[a.size()][];
            for (int i = 0; i < a.size(); i++) {
                List<Object> row = (List<Object>) a.get(i);
                ahead[i] = new double[row.size()];
                for (int k = 0; k < row.size(); k++) ahead[i][k] = row.get(k) instanceof Double d ? d : 0;
            }
            List<Object> r = (List<Object>) m.get("rays");
            rays = new double[r.size()];
            for (int i = 0; i < r.size(); i++) rays[i] = (Double) r.get(i);
            for (Object o : (List<Object>) m.get("opponents")) {
                Map<String, Object> om = (Map<String, Object>) o;
                opponents.add(new Opponent((int) Json.num(om, "id", 0), Json.num(om, "ds", 0), Json.num(om, "d", 0),
                        Json.num(om, "dd", 0), Json.num(om, "relSpeed", 0), Json.num(om, "ttc", -1), Json.num(om, "headingErr", 0)));
            }
            phase = Json.str((Map<String, Object>) m.get("race"), "phase", "green");
        }

        /** A number from the {@code car} object (s, d, speed, headingErr, yawRate, ... see PROTOCOL.md). */
        public double car(String key) {
            return Json.num(car, key, 0);
        }
    }

    public record Opponent(int id, double ds, double d, double dd, double relSpeed, double ttc, double headingErr) {}

    /** The {@code track} message: centerline samples every {@code step} m. */
    @SuppressWarnings("unchecked")
    public static final class Track {
        public final String layout;
        public final double length, step;
        /** Columns: s, x, y, z, heading, curvature, minD, maxD, bank, grade. */
        public final double[][] samples;

        Track(Map<String, Object> m) {
            layout = Json.str(m, "layout", "road");
            length = Json.num(m, "length", 1);
            step = Json.num(m, "step", 5);
            List<Object> rows = (List<Object>) m.get("samples");
            samples = new double[rows.size()][];
            for (int i = 0; i < rows.size(); i++) {
                List<Object> row = (List<Object>) rows.get(i);
                samples[i] = new double[row.size()];
                for (int k = 0; k < row.size(); k++) samples[i][k] = row.get(k) instanceof Double d ? d : 0;
            }
        }
    }
}
