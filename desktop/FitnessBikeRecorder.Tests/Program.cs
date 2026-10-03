using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using FitnessBikeRecorder.Core;
using FitnessBikeRecorder.PC;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;
using Makaretu.Dns;

internal static class Program
{
    private const string DeviceId = "11111111-1111-1111-1111-111111111111";
    private static readonly byte[] Key = Enumerable.Range(0, 16).Select(i => (byte)i).ToArray();
    private static int passed;

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            CheckDimming(); CheckProtocol(); CheckDiscovery(); CheckOverlay();
            if (args.FirstOrDefault() is { } preview) RenderSettings(preview);
            CheckConnectionAsync().GetAwaiter().GetResult();
            Console.WriteLine($"PASS: {passed} checks (dimming, protocol, native window, network lifecycle)");
            return 0;
        }
        catch (Exception error) { Console.Error.WriteLine(error); return 1; }
    }
    private static void Require(bool condition, string name) { if (!condition) throw new Exception(name); passed++; Console.WriteLine("PASS " + name); }
    private static void Near(double actual, double expected, string name) => Require(Math.Abs(actual - expected) < 0.00001, name);
    private static void Reject(Action operation, string name)
    {
        try { operation(); } catch (Exception) { Require(true, name); return; }
        throw new Exception("Did not reject: " + name);
    }
    private static void CheckDimming()
    {
        var dimmer = new Dimmer(new());
        Near(dimmer.Evaluate(0, false), 0, "stop starts grace period");
        Near(dimmer.Evaluate(4.9, false), 0, "five second grace");
        Near(dimmer.Evaluate(5, false), 0, "fade starts at five seconds");
        Near(dimmer.Evaluate(10, false), 0.4, "halfway through ten second fade");
        Near(dimmer.Evaluate(15, false), 0.8, "ten second fade reaches eighty percent");
        Near(dimmer.Evaluate(500, false), 0.8, "darkness is capped");
        Near(dimmer.Evaluate(501, true), 0.8, "resume starts without jump");
        Near(dimmer.Evaluate(501.5, true), 0.4, "resume halfway after half second");
        Near(dimmer.Evaluate(502, true), 0, "resume restores within one second");
        dimmer.Evaluate(503, false); dimmer.Evaluate(518, false);
        Near(dimmer.Evaluate(519, false, true), 0, "pause immediately clears overlay");
        Near(dimmer.Evaluate(519 + 1800, false), 0, "thirty minute pause expiry starts a new grace");
        Near(dimmer.Evaluate(519 + 1804.9, false), 0, "pause expiry grants full grace");
        dimmer.Evaluate(2400, false);
        Near(dimmer.Evaluate(2401, null), 0, "unknown or disconnected clears overlay");
        Near(dimmer.Evaluate(2402, false), 0, "reconnect starts fresh grace");
    }
    private static void CheckProtocol()
    {
        var challenge = new string('0', 64);
        const string payload = "{\"protocol\":1,\"deviceId\":\"11111111-1111-1111-1111-111111111111\",\"sequence\":1,\"bikeConnected\":true,\"dataFresh\":true,\"isActive\":false,\"cadenceRpm\":0,\"powerWatts\":0,\"speedKmh\":0}";
        const string signature = "5d754cb400ffd57d898398e8a32e795328b90b8c2661fdad018d14640013d65c";
        Require(Protocol.Proof(Key, challenge) == "0ddd31746e6498bcb259230029528c2fe895c8c1b343603737dabc8b2315f112", "Android-compatible connection fixture");
        Require(Protocol.Sign(Key, "telemetry\n" + challenge + "\n" + payload) == signature, "Android-compatible telemetry fixture");
        var envelope = JsonSerializer.Serialize(new { payload, signature });
        var state = Protocol.Verify(envelope, Key, challenge, DeviceId, 0);
        Require(state.Activity == false, "authenticated stopped telemetry");
        Require((state with { DataFresh = false }).Activity is null, "old BLE data is unknown");
        Require((state with { BikeConnected = false }).Activity is null, "BLE disconnect is unknown");
        Reject(() => Protocol.Verify(envelope, Key, challenge, DeviceId, 1), "replay is rejected");
        Reject(() => Protocol.Verify(envelope.Replace("cadenceRpm", "cadenceRPM"), Key, challenge, DeviceId, 0), "tampering is rejected");
        Reject(() => Protocol.Verify(envelope, new byte[16], challenge, DeviceId, 0), "wrong key is rejected");
        Reject(() => Protocol.Verify(envelope, Key, new string('1', 64), DeviceId, 0), "wrong session is rejected");
        Reject(() => Protocol.Verify(envelope, Key, challenge, Guid.NewGuid().ToString(), 0), "wrong phone is rejected");
        Require(Protocol.ParseCode("0001-0203 0405-0607 0809-0A0B 0C0D-0E0F").SequenceEqual(Key), "formatted pairing code is accepted");
        Reject(() => Protocol.ParseCode("123456"), "short pairing code is rejected");
    }
    private static void CheckOverlay()
    {
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        var foreground = GetForegroundWindow();
        using var overlay = new Overlay(new Rectangle(-20000, -20000, 100, 100));
        overlay.SetDim(0.4);
        var styles = GetWindowLongPtr(overlay.Handle, -20).ToInt64();
        Require((styles & 0x80000) != 0, "overlay is layered");
        Require((styles & 0x20) != 0, "overlay passes mouse input");
        Require((styles & 0x8000000) != 0, "overlay cannot activate");
        Require((styles & 0x80) != 0 && !overlay.ShowInTaskbar, "overlay is absent from taskbar and Alt-Tab");
        Require(GetForegroundWindow() == foreground, "overlay preserves foreground window");
        Require(GetLayeredWindowAttributes(overlay.Handle, out _, out var alpha, out _) && alpha == 102, "show preserves requested forty percent opacity");
        overlay.SetDim(0);
        Require(!overlay.Visible, "clear hides the window");
    }
    private static void CheckDiscovery()
    {
        var cache = new EndpointCache();
        var instance = "FBReco-" + DeviceId + "." + Protocol.ServiceType;
        var records = new ResourceRecord[]
        {
            new PTRRecord { Name = Protocol.ServiceType, DomainName = instance, TTL = TimeSpan.FromSeconds(120) },
            new SRVRecord { Name = instance, Target = "Phone.local", Port = 12345, TTL = TimeSpan.FromSeconds(120) },
            new TXTRecord { Name = instance, Strings = ["id=" + DeviceId, "protocol=1"], TTL = TimeSpan.FromSeconds(120) },
            new ARecord { Name = "phone.local", Address = IPAddress.Parse("192.168.1.20"), TTL = TimeSpan.FromSeconds(120) }
        };
        cache.Apply(records.Take(3), 0);
        Require(cache.Snapshot(1).Length == 0, "discovery waits for address records");
        cache.Apply(records.Skip(3), 1);
        var phone = cache.Snapshot(2).Single();
        Require(phone.DeviceId == DeviceId && phone.WebSocketUri.ToString() == "ws://192.168.1.20:12345/bike", "DNS-SD resolves UUID, IPv4 and assigned port across packets");
        Require(cache.Snapshot(121).Length == 0, "discovery expires old advertisements");
        cache.Apply(records, 200);
        cache.Apply([new SRVRecord { Name = instance, Target = "phone.local", Port = 12345, TTL = TimeSpan.Zero }], 201);
        Require(cache.Snapshot(202).Length == 0, "goodbye removes stopped phone");
        cache.Apply([records[1], new TXTRecord { Name = instance, Strings = ["id=invalid"], TTL = TimeSpan.Zero }], 203);
        Require(cache.Snapshot(204).Length == 0, "discovery requires a valid device identity");
    }
    private static void RenderSettings(string path)
    {
        using var client = new PhoneClient(() => new AppSettings());
        using var form = new SettingsForm(client, () => new AppSettings(), _ => { });
        form.StartPosition = FormStartPosition.Manual; form.Location = new Point(-20000, -20000);
        form.Show(); Application.DoEvents();
        using var bitmap = new Bitmap(form.Width, form.Height);
        form.DrawToBitmap(bitmap, new Rectangle(0, 0, bitmap.Width, bitmap.Height));
        bitmap.Save(path);
        form.Close();
    }
    private static async Task CheckConnectionAsync()
    {
        var firstSample = new TaskCompletionSource<BikeState>(TaskCreationOptions.RunContinuationsAsynchronously);
        var lostSample = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var activeSample = new TaskCompletionSource<BikeState>(TaskCreationOptions.RunContinuationsAsynchronously);
        var serverDone = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var builder = WebApplication.CreateSlimBuilder(); builder.Logging.ClearProviders();
        await using var app = builder.Build();
        app.Urls.Add("http://127.0.0.1:0"); app.UseWebSockets();
        var connections = 0;
        string? firstChallenge = null;
        app.Run(async context =>
        {
            try
            {
                var challenge = context.Request.Headers["X-FBReco-Challenge"].ToString();
                Require(context.Request.Headers["X-FBReco-Proof"] == Protocol.Proof(Key, challenge), "client handshake authenticates");
                using var socket = await context.WebSockets.AcceptWebSocketAsync();
                var connection = Interlocked.Increment(ref connections);
                if (connection == 1)
                {
                    firstChallenge = challenge;
                    await SendState(socket, challenge, false);
                    var buffer = new byte[64];
                    using var timeout = new CancellationTokenSource(4000);
                    var heartbeat = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), timeout.Token);
                    Require(Encoding.UTF8.GetString(buffer, 0, heartbeat.Count) == "ping", "client keeps Android socket alive");
                    await lostSample.Task.WaitAsync(TimeSpan.FromSeconds(8));
                }
                else
                {
                    Require(challenge != firstChallenge, "reconnect uses a new nonce");
                    await SendState(socket, challenge, true);
                    await activeSample.Task.WaitAsync(TimeSpan.FromSeconds(5));
                    serverDone.TrySetResult();
                }
            }
            catch (Exception error) { serverDone.TrySetException(error); }
        });
        await app.StartAsync();
        var port = new Uri(app.Urls.Single()).Port;
        var settings = new AppSettings { DeviceId = DeviceId, ProtectedCode = AppSettings.Protect(Key), ManualHost = "127.0.0.1", ManualPort = port };
        using var client = new PhoneClient(() => settings);
        client.Sample += state =>
        {
            if (state is null && firstSample.Task.IsCompleted) lostSample.TrySetResult();
            else if (state?.IsActive == true) activeSample.TrySetResult(state);
            else if (state is not null) firstSample.TrySetResult(state);
        };
        var worker = client.RunAsync();
        var stopped = await firstSample.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Require(stopped.Activity == false, "websocket delivers stop state");
        await lostSample.Task.WaitAsync(TimeSpan.FromSeconds(8));
        Require(true, "missing heartbeat clears state after five seconds");
        var active = await activeSample.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Require(active.Activity == true, "automatic reconnect delivers active state");
        await serverDone.Task.WaitAsync(TimeSpan.FromSeconds(5));
        client.Dispose(); await worker.WaitAsync(TimeSpan.FromSeconds(5));
        await app.StopAsync();
    }
    private static Task SendState(WebSocket socket, string challenge, bool active)
    {
        var payload = JsonSerializer.Serialize(new BikeState(1, DeviceId, 1, true, true, active, active ? 60 : 0, active ? 100 : 0, 0), Protocol.Json);
        var envelope = JsonSerializer.Serialize(new { payload, signature = Protocol.Sign(Key, "telemetry\n" + challenge + "\n" + payload) });
        return socket.SendAsync(new ArraySegment<byte>(Encoding.UTF8.GetBytes(envelope)), WebSocketMessageType.Text, true, CancellationToken.None);
    }
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")] private static extern IntPtr GetWindowLongPtr(IntPtr hwnd, int index);
    [DllImport("user32.dll")][return: MarshalAs(UnmanagedType.Bool)] private static extern bool GetLayeredWindowAttributes(IntPtr hwnd, out uint color, out byte alpha, out uint flags);
}
