import racingsim.bot.Bot;

/**
 * The baseline bot, the same algorithm and numbers as bots/python/examples/baseline.py:
 * <pre>
 *   javac -d bots/java/out bots/java/src/racingsim/bot/*.java bots/java/examples/Baseline.java
 *   java -cp bots/java/out Baseline [--port 4510]
 * </pre>
 */
public class Baseline extends Bot {

    static final double GRIP_USE = 0.85;                     // share of the tires' grip planned on
    static final double AERO_GRIP_ROAD = 0.0025, AERO_GRIP_OVAL = 0.0010;   // cornering grip per (m/s)^2 of downforce
    static final double BRAKE_DECEL_ROAD = 16.0, BRAKE_DECEL_OVAL = 9.0;    // m/s^2 planned under braking
    static final double LOOK_BASE = 8.0, LOOK_PER_SPEED = 0.35;            // steering look-ahead (m, + per m/s)
    static final double STEER_GAIN = 2.2, YAW_DAMP = 0.25;
    static final double PASS_OFFSET = 3.2, SPEED_MARGIN = 0.5;

    private double lane;

    public Baseline() {
        super("BaselineJava");
        color1 = "#e53935";
        color2 = "#ffffff";
    }

    @Override
    public Control onTick(State st) {
        boolean road = track == null || track.layout.equals("road");
        double v = Math.max(st.car("speed"), 1.0), s = st.car("s"), d = st.car("d");

        // the lane: the middle of the road, or beside a car close ahead
        double targetD = 0;
        for (Opponent o : st.opponents) {
            if (o.ds() > 0 && o.ds() < 30 && Math.abs(o.dd()) < 2.5 && o.relSpeed() < 0) {
                targetD = o.d() + (o.d() > 0 ? -1 : 1) * PASS_OFFSET;
                break;
            }
        }
        double lo = Double.NEGATIVE_INFINITY, hi = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 6; i++) {
            lo = Math.max(lo, st.ahead[i][2]);
            hi = Math.min(hi, st.ahead[i][3]);
        }
        lo += 1.8;
        hi -= 1.8;
        targetD = lo < hi ? Math.min(Math.max(targetD, lo), hi) : 0;
        lane += (targetD - lane) * 0.15;

        // steering: aim at the lane look metres on
        double look = LOOK_BASE + LOOK_PER_SPEED * v;
        double k = curvAt(st.ahead, look * 0.5);
        double lateral = lane - d - k * look * look / 2;
        double wantHeading = Math.atan2(lateral, look);
        double wantYaw = -k * v;
        double steer = STEER_GAIN * (wantHeading - st.car("headingErr")) + YAW_DAMP * (wantYaw - st.car("yawRate"));
        steer = Math.max(-1, Math.min(1, steer));

        // speed
        double muG = GRIP_USE * ((Number) ((java.util.Map<?, ?>) welcome.get("car")).get("tireMu")).doubleValue() * 9.81;
        double aero = road ? AERO_GRIP_ROAD : AERO_GRIP_OVAL;
        double dec = road ? BRAKE_DECEL_ROAD : BRAKE_DECEL_OVAL;
        double target = 200;
        for (int i = -1; i < st.ahead.length; i++) {
            double x = i < 0 ? 0 : st.ahead[i][0], kk = st.ahead[Math.max(i, 0)][1];
            double room = Math.abs(kk) - aero;
            double vc = room > 1e-5 ? Math.sqrt(muG / room) : 200;
            target = Math.min(target, Math.sqrt(vc * vc + 2 * dec * x));
        }
        for (Opponent o : st.opponents) {
            if (o.ds() > 0 && o.ds() < 25 && Math.abs(o.dd()) < 2.2) target = Math.min(target, v + o.relSpeed() + (o.ds() - 8) * 0.5);
        }
        double speed = st.car("speed"), throttle, brake;
        if (speed < target) {
            throttle = Math.min(1, (target - speed) * 0.5 + 0.3);
            brake = 0;
        } else if (speed > target + SPEED_MARGIN) {
            throttle = 0;
            brake = Math.min(1, (speed - target) * 0.25);
        } else {
            throttle = 0.2;
            brake = 0;
        }
        if (!st.phase.equals("green")) {
            throttle = 0;
            if (st.phase.equals("lights")) brake = 1;
        }
        return new Control(steer, throttle, brake).line(s, d, s + look, lane, "#ff0000");
    }

    /** Curvature x metres ahead (linear between the samples). */
    static double curvAt(double[][] ahead, double x) {
        double[] prev = ahead[0];
        for (double[] a : ahead) {
            if (a[0] >= x) {
                if (a == prev) return a[1];
                double t = (x - prev[0]) / (a[0] - prev[0]);
                return prev[1] + (a[1] - prev[1]) * t;
            }
            prev = a;
        }
        return ahead[ahead.length - 1][1];
    }

    @Override
    public void onEvent(java.util.Map<String, Object> ev) {
        if ("lapComplete".equals(ev.get("event"))) System.out.printf("lap %.0f: %.2fs  P%.0f%n", ev.get("lap"), ev.get("lapTime"), ev.get("position"));
    }

    @Override
    public void onFinish(java.util.Map<String, Object> result) {
        for (Object o : (java.util.List<?>) result.get("order")) {
            java.util.Map<?, ?> c = (java.util.Map<?, ?>) o;
            if (Boolean.TRUE.equals(c.get("bot"))) {
                System.out.printf("finished P%d  best lap %s  contacts %d%n", ((Number) c.get("position")).intValue(),
                        c.get("bestLap"), ((Number) c.get("contacts")).intValue());
            }
        }
    }

    public static void main(String[] args) {
        new Baseline().run(args);
    }
}
