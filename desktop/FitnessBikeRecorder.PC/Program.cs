namespace FitnessBikeRecorder.PC;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        using var mutex = new Mutex(true, @"Local\FitnessBikeRecorderPC", out var first);
        if (!first) return;
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        AppSettings settings;
        try { settings = args.Contains("--smoke-test") ? new() : AppSettings.Load(); }
        catch (Exception) { settings = new(); MessageBox.Show("設定を読み込めませんでした。トレイの設定から再登録してください。", "Fitness Bike Recorder", MessageBoxButtons.OK, MessageBoxIcon.Information); }
        using var app = new TrayApp(settings);
        // Used by the build's smoke check; does not change pairing or autostart settings.
        using var smokeTimer = new System.Windows.Forms.Timer { Interval = 2000 };
        if (args.Contains("--smoke-test")) { smokeTimer.Tick += (_, _) => app.ExitThread(); smokeTimer.Start(); }
        Application.Run(app);
    }
}
