using System.Net;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using FitnessBikeRecorder.Core;

namespace FitnessBikeRecorder.PC;

internal sealed class PhoneClient(Func<AppSettings> getSettings) : IDisposable
{
    private readonly CancellationTokenSource lifetime = new();
    private Discovery? discovery;
    private ClientWebSocket? currentSocket;
    private int version;
    public event Action<BikeState?>? Sample;
    public event Action<string>? Status;
    public PhoneEndpoint[] Phones => Volatile.Read(ref discovery)?.Snapshot() ?? [];
    public void Reconfigure() { Interlocked.Increment(ref version); Volatile.Read(ref currentSocket)?.Abort(); }
    public void Refresh() { try { Volatile.Read(ref discovery)?.Query(); } catch (Exception) { /* retry in RunAsync */ } }

    public async Task RunAsync()
    {
        while (!lifetime.IsCancellationRequested)
        {
            try
            {
                // Manual connections remain usable when multicast is blocked or unavailable.
                try { discovery ??= new Discovery(); discovery.Query(); }
                catch (Exception) { discovery?.Dispose(); discovery = null; }
                var settings = getSettings();
                var key = settings.Key;
                if (key is null || settings.DeviceId is null) Status?.Invoke("未登録 — 設定からスマホを登録してください");
                else
                {
                    var endpoints = Phones.Where(p => p.DeviceId == settings.DeviceId).ToList();
                    if (IPAddress.TryParse(settings.ManualHost, out var host) && settings.ManualPort is > 0 and <= 65535)
                        endpoints.Add(new(settings.DeviceId, "手動接続", host, settings.ManualPort));
                    if (endpoints.Count == 0) Status?.Invoke("スマホを検索中");
                    foreach (var endpoint in endpoints.DistinctBy(p => p.WebSocketUri))
                    {
                        if (lifetime.IsCancellationRequested) break;
                        await ConnectAsync(endpoint, key, Volatile.Read(ref version));
                    }
                }
            }
            catch (Exception) when (lifetime.IsCancellationRequested) { break; }
            catch (Exception) when (!lifetime.IsCancellationRequested)
            {
                Status?.Invoke("ネットワーク待機中 — 再接続します");
                discovery?.Dispose(); discovery = null;
            }
            Sample?.Invoke(null);
            try { await Task.Delay(3000, lifetime.Token); } catch (OperationCanceledException) { break; }
        }
    }

    private async Task ConnectAsync(PhoneEndpoint endpoint, byte[] key, int generation)
    {
        using var socket = new ClientWebSocket();
        Volatile.Write(ref currentSocket, socket);
        var challenge = Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32));
        socket.Options.SetRequestHeader("X-FBReco-Challenge", challenge);
        socket.Options.SetRequestHeader("X-FBReco-Proof", Protocol.Proof(key, challenge));
        socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(2);
        using var heartbeatLifetime = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
        Task? heartbeat = null;
        try
        {
            using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token))
            {
                timeout.CancelAfter(TimeSpan.FromSeconds(3));
                await socket.ConnectAsync(endpoint.WebSocketUri, timeout.Token);
            }
            heartbeat = Task.Run(async () =>
            {
                try
                {
                    while (!heartbeatLifetime.IsCancellationRequested)
                    {
                        await socket.SendAsync(Encoding.UTF8.GetBytes("ping"), WebSocketMessageType.Text, true, heartbeatLifetime.Token);
                        await Task.Delay(2000, heartbeatLifetime.Token);
                    }
                }
                catch (Exception) { socket.Abort(); }
            });
            var buffer = new byte[8192];
            long sequence = 0;
            while (!lifetime.IsCancellationRequested && generation == Volatile.Read(ref version))
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
                timeout.CancelAfter(TimeSpan.FromSeconds(5));
                var count = 0;
                WebSocketReceiveResult message;
                do
                {
                    if (count == buffer.Length) throw new InvalidDataException("Frame too large");
                    message = await socket.ReceiveAsync(new ArraySegment<byte>(buffer, count, buffer.Length - count), timeout.Token);
                    if (message.MessageType != WebSocketMessageType.Text) throw new InvalidDataException("Unexpected frame");
                    count += message.Count;
                } while (!message.EndOfMessage);
                var state = Protocol.Verify(Encoding.UTF8.GetString(buffer, 0, count), key, challenge, endpoint.DeviceId, sequence);
                sequence = state.Sequence;
                if (generation != Volatile.Read(ref version)) break;
                Sample?.Invoke(state);
                Status?.Invoke(state.Activity switch
                {
                    true => "接続済み — 走行中",
                    false => "接続済み — 停止中",
                    _ => "接続済み — バイクのデータ待ち"
                });
            }
        }
        catch (Exception) when (lifetime.IsCancellationRequested) { }
        catch (Exception) when (!lifetime.IsCancellationRequested)
        {
            if (generation == Volatile.Read(ref version)) Status?.Invoke("再接続待ち — Wi-Fiと接続コードを確認してください");
        }
        finally
        {
            heartbeatLifetime.Cancel();
            if (heartbeat is not null) await heartbeat;
            Interlocked.CompareExchange(ref currentSocket, null, socket);
            Sample?.Invoke(null);
        }
    }
    public void Dispose() { lifetime.Cancel(); Volatile.Read(ref currentSocket)?.Abort(); discovery?.Dispose(); }
}
