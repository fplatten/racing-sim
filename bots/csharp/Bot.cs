// Racing Sim bot SDK for C# (.NET 8, System.Text.Json only). See bots/PROTOCOL.md.
//
//   class MyBot : Bot {
//       public MyBot() : base("MyBot") { }
//       public override Control OnTick(State s) => new Control(0, 1, 0);
//   }
//   new MyBot().Run(args);
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace RacingSim.Bots;

/// <summary>A driving command: steer -1 (left) .. 1 (right), throttle and brake 0..1, shift -1/0/+1 (manual gearbox only).</summary>
public sealed class Control
{
    public double Steer, Throttle, Brake;
    public int Shift;
    /// <summary>Lines drawn over the track: (s1, d1, s2, d2, "#rrggbb").</summary>
    public readonly List<(double S1, double D1, double S2, double D2, string Color)> Debug = new();

    public Control(double steer, double throttle, double brake)
    {
        Steer = steer;
        Throttle = throttle;
        Brake = brake;
    }

    public Control Line(double s1, double d1, double s2, double d2, string color)
    {
        Debug.Add((s1, d1, s2, d2, color));
        return this;
    }
}

public sealed record Opponent(int Id, double Ds, double D, double Dd, double RelSpeed, double Ttc, double HeadingErr);

/// <summary>The <c>state</c> message, unpacked.</summary>
public sealed class State
{
    public readonly long Tick;
    public readonly double T;
    public readonly JsonObject Car;
    /// <summary>[distance, curvature, minD, maxD] at +10 .. +300 m.</summary>
    public readonly double[][] Ahead;
    public readonly double[] Rays;
    public readonly List<Opponent> Opponents = new();
    public readonly string Phase;
    public readonly JsonObject Raw;

    internal State(JsonObject m)
    {
        Raw = m;
        Tick = m["tick"]!.GetValue<long>();
        T = Num(m, "t");
        Car = m["car"]!.AsObject();
        Ahead = m["ahead"]!.AsArray().Select(r => r!.AsArray().Select(x => x?.GetValue<double>() ?? 0).ToArray()).ToArray();
        Rays = m["rays"]!.AsArray().Select(x => x?.GetValue<double>() ?? 0).ToArray();
        foreach (var o in m["opponents"]!.AsArray())
        {
            var om = o!.AsObject();
            Opponents.Add(new Opponent((int)Num(om, "id"), Num(om, "ds"), Num(om, "d"), Num(om, "dd"), Num(om, "relSpeed"),
                Num(om, "ttc", -1), Num(om, "headingErr")));
        }
        Phase = m["race"]?["phase"]?.GetValue<string>() ?? "green";
    }

    /// <summary>A number from the car object (s, d, speed, headingErr, yawRate, ... see PROTOCOL.md).</summary>
    public double CarValue(string key) => Num(Car, key);

    internal static double Num(JsonObject o, string key, double def = 0) =>
        o[key] is JsonValue v && v.TryGetValue<double>(out var d) ? d : def;
}

/// <summary>The <c>track</c> message: centerline samples every Step m (s, x, y, z, heading, curvature, minD, maxD, bank, grade).</summary>
public sealed class Track
{
    public readonly string Layout;
    public readonly double Length, Step;
    public readonly double[][] Samples;

    internal Track(JsonObject m)
    {
        Layout = m["layout"]!.GetValue<string>();
        Length = m["length"]!.GetValue<double>();
        Step = m["step"]!.GetValue<double>();
        Samples = m["samples"]!.AsArray().Select(r => r!.AsArray().Select(x => x?.GetValue<double>() ?? 0).ToArray()).ToArray();
    }
}

public abstract class Bot
{
    public const int Protocol = 1, DefaultPort = 4510;

    protected readonly string Name;
    protected string? Color1, Color2;
    protected Track? Track;
    protected JsonObject? Welcome;

    protected Bot(string name) => Name = name;

    /// <summary>Every control tick (30 a second by default).</summary>
    public abstract Control OnTick(State state);
    public virtual void OnWelcome(JsonObject welcome) { }
    public virtual void OnTrack(Track track) { }
    public virtual void OnEvent(JsonObject ev) { }
    public virtual void OnFinish(JsonObject result) { }

    /// <summary>Connects (--port N, else RACINGSIM_BOTS, else 4510) and drives until the race ends.</summary>
    public void Run(string[] args)
    {
        int port = DefaultPort;
        if (int.TryParse(Environment.GetEnvironmentVariable("RACINGSIM_BOTS"), out var envPort)) port = envPort;
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--port" && i + 1 < args.Length) port = int.Parse(args[i + 1]);
            else if (args[i].StartsWith("--port=")) port = int.Parse(args[i][7..]);
        }
        Run("127.0.0.1", port);
    }

    public void Run(string host, int port)
    {
        TcpClient? client = null;
        var deadline = DateTime.UtcNow.AddSeconds(30);
        while (client == null)
        {
            try { client = new TcpClient(host, port); }
            catch (SocketException)
            {
                if (DateTime.UtcNow > deadline) throw new InvalidOperationException($"{Name}: no game on {host}:{port}");
                Thread.Sleep(500);
            }
        }
        using (client)
        {
            client.NoDelay = true;
            var stream = client.GetStream();
            using var reader = new StreamReader(stream, new UTF8Encoding(false));
            var hello = new JsonObject { ["type"] = "hello", ["protocol"] = Protocol, ["name"] = Name };
            if (Color1 != null) hello["color1"] = Color1;
            if (Color2 != null) hello["color2"] = Color2;
            Send(stream, hello);
            Console.WriteLine($"{Name}: connected to {host}:{port}");
            try
            {
                string? line;
                while ((line = reader.ReadLine()) != null)
                {
                    var m = JsonNode.Parse(line)!.AsObject();
                    switch (m["type"]?.GetValue<string>())
                    {
                        case "state":
                            var st = new State(m);
                            var c = OnTick(st) ?? new Control(0, 0, 0);
                            var o = new JsonObject
                            {
                                ["type"] = "control", ["tick"] = st.Tick, ["steer"] = c.Steer, ["throttle"] = c.Throttle,
                                ["brake"] = c.Brake, ["shift"] = c.Shift,
                            };
                            if (c.Debug.Count > 0)
                            {
                                var lines = new JsonArray();
                                foreach (var l in c.Debug) lines.Add(new JsonArray(l.S1, l.D1, l.S2, l.D2, l.Color));
                                o["debug"] = new JsonObject { ["lines"] = lines };
                            }
                            Send(stream, o);
                            break;
                        case "welcome":
                            Welcome = m;
                            OnWelcome(m);
                            break;
                        case "track":
                            Track = new Track(m);
                            OnTrack(Track);
                            break;
                        case "event":
                            OnEvent(m);
                            break;
                        case "bye":
                            OnFinish(m);
                            return;
                    }
                }
            }
            catch (IOException)
            {
                Console.WriteLine($"{Name}: the game closed the connection");
            }
        }
    }

    private static void Send(NetworkStream s, JsonObject o)
    {
        var bytes = Encoding.UTF8.GetBytes(o.ToJsonString() + "\n");
        s.Write(bytes, 0, bytes.Length);
    }
}
