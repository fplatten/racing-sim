// The baseline bot, the same algorithm and numbers as bots/python/examples/baseline.py.
//   dotnet run --project bots/csharp -- [--port 4510]
using System.Text.Json.Nodes;

namespace RacingSim.Bots;

public sealed class Baseline : Bot
{
    const double GripUse = 0.85;                          // share of the tires' grip planned on
    const double AeroGripRoad = 0.0025, AeroGripOval = 0.0010;  // cornering grip per (m/s)^2 of downforce
    const double BrakeDecelRoad = 16.0, BrakeDecelOval = 9.0;   // m/s^2 planned under braking
    const double LookBase = 8.0, LookPerSpeed = 0.35;           // steering look-ahead (m, + per m/s)
    const double SteerGain = 2.2, YawDamp = 0.25;
    const double PassOffset = 3.2, SpeedMargin = 0.5;

    double lane;

    public Baseline() : base("BaselineCs")
    {
        Color1 = "#43a047";
        Color2 = "#ffffff";
    }

    public override Control OnTick(State st)
    {
        bool road = Track == null || Track.Layout == "road";
        double v = Math.Max(st.CarValue("speed"), 1.0), s = st.CarValue("s"), d = st.CarValue("d");

        // the lane: the middle of the road, or beside a car close ahead
        double targetD = 0;
        foreach (var o in st.Opponents)
        {
            if (o.Ds > 0 && o.Ds < 30 && Math.Abs(o.Dd) < 2.5 && o.RelSpeed < 0)
            {
                targetD = o.D + (o.D > 0 ? -1 : 1) * PassOffset;
                break;
            }
        }
        double lo = double.NegativeInfinity, hi = double.PositiveInfinity;
        for (int i = 0; i < 6; i++)
        {
            lo = Math.Max(lo, st.Ahead[i][2]);
            hi = Math.Min(hi, st.Ahead[i][3]);
        }
        lo += 1.8;
        hi -= 1.8;
        targetD = lo < hi ? Math.Min(Math.Max(targetD, lo), hi) : 0;
        lane += (targetD - lane) * 0.15;

        // steering: aim at the lane look metres on
        double look = LookBase + LookPerSpeed * v;
        double k = CurvAt(st.Ahead, look * 0.5);
        double lateral = lane - d - k * look * look / 2;
        double wantHeading = Math.Atan2(lateral, look);
        double wantYaw = -k * v;
        double steer = SteerGain * (wantHeading - st.CarValue("headingErr")) + YawDamp * (wantYaw - st.CarValue("yawRate"));
        steer = Math.Clamp(steer, -1, 1);

        // speed
        double muG = GripUse * Welcome!["car"]!["tireMu"]!.GetValue<double>() * 9.81;
        double aero = road ? AeroGripRoad : AeroGripOval;
        double dec = road ? BrakeDecelRoad : BrakeDecelOval;
        double target = 200;
        for (int i = -1; i < st.Ahead.Length; i++)
        {
            double x = i < 0 ? 0 : st.Ahead[i][0], kk = st.Ahead[Math.Max(i, 0)][1];
            double room = Math.Abs(kk) - aero;
            double vc = room > 1e-5 ? Math.Sqrt(muG / room) : 200;
            target = Math.Min(target, Math.Sqrt(vc * vc + 2 * dec * x));
        }
        foreach (var o in st.Opponents)
        {
            if (o.Ds > 0 && o.Ds < 25 && Math.Abs(o.Dd) < 2.2) target = Math.Min(target, v + o.RelSpeed + (o.Ds - 8) * 0.5);
        }
        double speed = st.CarValue("speed"), throttle, brake;
        if (speed < target) { throttle = Math.Min(1, (target - speed) * 0.5 + 0.3); brake = 0; }
        else if (speed > target + SpeedMargin) { throttle = 0; brake = Math.Min(1, (speed - target) * 0.25); }
        else { throttle = 0.2; brake = 0; }
        if (st.Phase != "green")
        {
            throttle = 0;
            if (st.Phase == "lights") brake = 1;
        }
        return new Control(steer, throttle, brake).Line(s, d, s + look, lane, "#00c853");
    }

    /// <summary>Curvature x metres ahead (linear between the samples).</summary>
    static double CurvAt(double[][] ahead, double x)
    {
        var prev = ahead[0];
        foreach (var a in ahead)
        {
            if (a[0] >= x)
            {
                if (ReferenceEquals(a, prev)) return a[1];
                double t = (x - prev[0]) / (a[0] - prev[0]);
                return prev[1] + (a[1] - prev[1]) * t;
            }
            prev = a;
        }
        return ahead[^1][1];
    }

    public override void OnEvent(JsonObject ev)
    {
        if (ev["event"]?.GetValue<string>() == "lapComplete")
            Console.WriteLine($"lap {ev["lap"]}: {ev["lapTime"]!.GetValue<double>():F2}s  P{ev["position"]}");
    }

    public override void OnFinish(JsonObject result)
    {
        foreach (var c in result["order"]!.AsArray())
            if (c!["bot"]!.GetValue<bool>())
                Console.WriteLine($"finished P{c["position"]}  best lap {c["bestLap"]}  contacts {c["contacts"]}");
    }

    public static void Main(string[] args) => new Baseline().Run(args);
}
