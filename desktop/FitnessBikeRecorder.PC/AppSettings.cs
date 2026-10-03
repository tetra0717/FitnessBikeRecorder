using System.Security.Cryptography;
using System.Text.Json;
using FitnessBikeRecorder.Core;
using Microsoft.Win32;

namespace FitnessBikeRecorder.PC;

internal sealed record AppSettings
{
    public string? DeviceId { get; init; }
    public string? ProtectedCode { get; init; }
    public double GraceSeconds { get; init; } = 5;
    public double FadeSeconds { get; init; } = 10;
    public double MaximumOpacity { get; init; } = 0.8;
    public string? ManualHost { get; init; }
    public int ManualPort { get; init; }
    public DimmerSettings Dimmer => new(GraceSeconds, FadeSeconds, MaximumOpacity);
    public byte[]? Key => ProtectedCode is null ? null : ProtectedData.Unprotect(Convert.FromBase64String(ProtectedCode), null, DataProtectionScope.CurrentUser);
    public static string Protect(byte[] key) => Convert.ToBase64String(ProtectedData.Protect(key, null, DataProtectionScope.CurrentUser));
    public static readonly string Folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "FitnessBikeRecorderPC");
    private static string FilePath => Path.Combine(Folder, "settings.json");

    public static AppSettings Load()
    {
        if (!File.Exists(FilePath)) return new();
        var result = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath), Protocol.Json) ?? new();
        if (result.GraceSeconds is < 0 or > 300 || result.FadeSeconds is < 1 or > 300
            || result.MaximumOpacity is < 0.1 or > 0.9 || !double.IsFinite(result.GraceSeconds)
            || !double.IsFinite(result.FadeSeconds) || !double.IsFinite(result.MaximumOpacity))
            throw new InvalidDataException("設定の値が範囲外です。");
        _ = result.Key; // Validate DPAPI before the tray starts.
        return result;
    }

    public void Save()
    {
        Directory.CreateDirectory(Folder);
        // Do not serialize the decrypted Key or derived Dimmer property.
        var value = new { DeviceId, ProtectedCode, GraceSeconds, FadeSeconds, MaximumOpacity, ManualHost, ManualPort };
        var temporary = FilePath + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(value, Protocol.Json));
        File.Move(temporary, FilePath, true);
    }
}

internal static class AutoStart
{
    private const string Name = "FitnessBikeRecorderPC";
    private const string RegistryPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    public static bool Enabled
    {
        get { using var key = Registry.CurrentUser.OpenSubKey(RegistryPath); return key?.GetValue(Name) is string; }
        set
        {
            using var key = Registry.CurrentUser.CreateSubKey(RegistryPath);
            if (value) key.SetValue(Name, "\"" + Environment.ProcessPath + "\"");
            else key.DeleteValue(Name, false);
        }
    }
}
