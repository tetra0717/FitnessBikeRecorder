using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace FitnessBikeRecorder.Core;

public sealed record BikeState(int Protocol, string DeviceId, long Sequence, bool BikeConnected,
    bool DataFresh, bool IsActive, double CadenceRpm, int PowerWatts, double SpeedKmh)
{
    public bool? Activity => BikeConnected && DataFresh ? IsActive : null;
}

public static class Protocol
{
    public const string ServiceType = "_fbreco._tcp.local";
    public static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true };

    public static string NormalizeCode(string input) => new(input.Where(c => !char.IsWhiteSpace(c) && c != '-').ToArray());
    public static byte[] ParseCode(string input)
    {
        var normalized = NormalizeCode(input);
        if (normalized.Length != 32 || !normalized.All(Uri.IsHexDigit))
            throw new FormatException("スマホに表示された32桁の接続コードを入力してください。");
        return Convert.FromHexString(normalized);
    }
    public static string Proof(byte[] key, string challenge) => Sign(key, "connect\n" + challenge);
    public static string Sign(byte[] key, string value) => Convert.ToHexStringLower(HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(value)));

    public static BikeState Verify(string envelope, byte[] key, string challenge, string deviceId, long previousSequence)
    {
        using var doc = JsonDocument.Parse(envelope);
        var payload = doc.RootElement.GetProperty("payload").GetString() ?? throw new InvalidDataException("Missing payload");
        var signature = Convert.FromHexString(doc.RootElement.GetProperty("signature").GetString() ?? "");
        var expected = Convert.FromHexString(Sign(key, "telemetry\n" + challenge + "\n" + payload));
        if (!CryptographicOperations.FixedTimeEquals(signature, expected)) throw new InvalidDataException("Invalid signature");
        var state = JsonSerializer.Deserialize<BikeState>(payload, Json) ?? throw new InvalidDataException("Missing state");
        if (state.Protocol != 1 || state.DeviceId != deviceId || state.Sequence <= previousSequence
            || !double.IsFinite(state.CadenceRpm) || !double.IsFinite(state.SpeedKmh)
            || state.CadenceRpm < 0 || state.SpeedKmh < 0)
            throw new InvalidDataException("Invalid or replayed state");
        return state;
    }
}
