using System.Net;
using System.Net.Sockets;
using FitnessBikeRecorder.Core;
using Makaretu.Dns;

namespace FitnessBikeRecorder.PC;

internal sealed record PhoneEndpoint(string DeviceId, string Name, IPAddress Address, int Port)
{
    public override string ToString() => $"{Name}  ({Address})";
    public Uri WebSocketUri => new UriBuilder("ws", Address.ToString(), Port, "/bike").Uri;
}

internal sealed class EndpointCache
{
    private readonly object gate = new();
    private readonly Dictionary<string, (SRVRecord Record, double Until)> servers = [];
    private readonly Dictionary<string, (string Id, double Until)> identities = [];
    private readonly Dictionary<string, (IPAddress Address, double Until)> addresses = [];
    private static string Canonical(DomainName name) => name.ToString().TrimEnd('.').ToLowerInvariant();

    public string[] Apply(IEnumerable<ResourceRecord> records, double now)
    {
        var queries = new HashSet<string>();
        lock (gate)
        {
            foreach (var record in records)
            {
                var name = Canonical(record.Name);
                var until = now + Math.Min(record.TTL.TotalSeconds, 120);
                if (record is PTRRecord ptr && name == Protocol.ServiceType)
                    queries.Add(Canonical(ptr.DomainName));
                if (name.EndsWith("." + Protocol.ServiceType, StringComparison.Ordinal))
                {
                    if (record is SRVRecord srv)
                    {
                        if (srv.TTL <= TimeSpan.Zero) servers.Remove(name);
                        else servers[name] = (srv, until);
                        queries.Add(Canonical(srv.Target));
                    }
                    if (record is TXTRecord txt)
                    {
                        var id = txt.Strings.FirstOrDefault(s => s.StartsWith("id=", StringComparison.Ordinal))?[3..];
                        if (txt.TTL <= TimeSpan.Zero) identities.Remove(name);
                        else if (Guid.TryParse(id, out _)) identities[name] = (id!, until);
                    }
                }
                if (record is ARecord address)
                {
                    if (address.TTL <= TimeSpan.Zero) addresses.Remove(name);
                    else addresses[name] = (address.Address, until);
                }
            }
            Prune(servers, now); Prune(identities, now); Prune(addresses, now);
        }
        return queries.ToArray();
    }

    private static void Prune<T>(Dictionary<string, (T, double Until)> cache, double now)
    {
        foreach (var key in cache.Where(pair => pair.Value.Until <= now).Select(pair => pair.Key).ToArray()) cache.Remove(key);
        if (cache.Count > 256) cache.Clear();
    }

    public PhoneEndpoint[] Snapshot(double now)
    {
        lock (gate)
        {
            var result = new List<PhoneEndpoint>();
            foreach (var (name, server) in servers)
            {
                if (server.Until <= now || server.Record.Port == 0
                    || !identities.TryGetValue(name, out var identity) || identity.Until <= now
                    || !addresses.TryGetValue(Canonical(server.Record.Target), out var address) || address.Until <= now
                    || address.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                result.Add(new(identity.Id, "FBReco " + identity.Id[..8], address.Address, server.Record.Port));
            }
            return result.OrderBy(p => p.DeviceId).ToArray();
        }
    }
}

internal sealed class Discovery : IDisposable
{
    private readonly MulticastService mdns = new();
    private readonly EndpointCache cache = new();
    private static double Now => Environment.TickCount64 / 1000.0;
    public Discovery()
    {
        mdns.AnswerReceived += OnAnswer;
        mdns.NetworkInterfaceDiscovered += (_, _) => Query();
        try { mdns.Start(); Query(); } catch { mdns.Dispose(); throw; }
    }
    public void Query() => mdns.SendQuery(Protocol.ServiceType, type: DnsType.PTR);
    private void OnAnswer(object? sender, MessageEventArgs args)
    {
        var queries = cache.Apply(args.Message.Answers.Concat(args.Message.AdditionalRecords), Now);
        foreach (var query in queries)
        {
            try { mdns.SendQuery(query, type: DnsType.ANY); }
            catch (ObjectDisposedException) { return; }
        }
    }
    public PhoneEndpoint[] Snapshot() => cache.Snapshot(Now);
    public void Dispose() { mdns.AnswerReceived -= OnAnswer; mdns.Dispose(); }
}
