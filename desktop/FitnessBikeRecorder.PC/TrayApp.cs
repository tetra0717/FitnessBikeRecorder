using System.Diagnostics;
using System.Runtime.InteropServices;
using FitnessBikeRecorder.Core;
using Microsoft.Win32;

namespace FitnessBikeRecorder.PC;

internal sealed class TrayApp : ApplicationContext
{
    private readonly Control dispatcher = new();
    private readonly NotifyIcon tray;
    private readonly ContextMenuStrip menu = new();
    private readonly ToolStripMenuItem stateItem = new() { Enabled = false };
    private readonly ToolStripMenuItem autoStart = new("ログイン時に自動起動") { CheckOnClick = false };
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 33 };
    private readonly Stopwatch clock = Stopwatch.StartNew();
    private readonly List<Overlay> overlays = [];
    private readonly PhoneClient client;
    private readonly Icon runningIcon = MakeIcon(Color.FromArgb(39, 140, 185));
    private readonly Icon pausedIcon = MakeIcon(Color.FromArgb(209, 141, 41));
    private AppSettings settings;
    private Dimmer dimmer;
    private BikeState? sample;
    private double receivedAt;
    private double pauseUntil;
    private string connectionStatus = "スマホを検索中";
    private SettingsForm? settingsForm;
    private bool suspended;
    private bool closing;
    private double appliedOpacity = -1;
    public TrayApp(AppSettings settings)
    {
        this.settings = settings; dimmer = new(settings.Dimmer);
        _ = dispatcher.Handle;
        client = new(() => Volatile.Read(ref this.settings));
        client.Sample += value => Post(() => { sample = value; receivedAt = clock.Elapsed.TotalSeconds; });
        client.Status += value => Post(() => { connectionStatus = value; });
        menu.Items.Add(stateItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("30分停止", null, (_, _) => Pause(30));
        menu.Items.Add("1時間停止", null, (_, _) => Pause(60));
        menu.Items.Add("今すぐ再開", null, (_, _) => { pauseUntil = 0; dimmer.Reset(); Update(); });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(autoStart);
        autoStart.Click += (_, _) =>
        {
            try { AutoStart.Enabled = !AutoStart.Enabled; autoStart.Checked = AutoStart.Enabled; }
            catch (Exception) { tray!.ShowBalloonTip(4000, "自動起動", "自動起動の設定を保存できませんでした。", ToolTipIcon.Warning); }
        };
        menu.Items.Add("設定", null, (_, _) => OpenSettings());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("終了", null, (_, _) => ExitThread());
        menu.Opening += (_, _) => { autoStart.Checked = AutoStart.Enabled; UpdateStatus(); };
        tray = new NotifyIcon { Icon = runningIcon, ContextMenuStrip = menu, Text = "Fitness Bike Recorder", Visible = true };
        RebuildOverlays();
        SystemEvents.DisplaySettingsChanged += DisplayChanged;
        SystemEvents.PowerModeChanged += PowerChanged;
        timer.Tick += (_, _) => Update(); timer.Start();
        _ = Task.Run(client.RunAsync);
        if (settings.DeviceId is null) tray.ShowBalloonTip(5000, "Fitness Bike Recorder", "アイコンを右クリック → 設定でスマホを登録してください。", ToolTipIcon.Info);
    }
    private void Post(Action action)
    {
        if (closing || dispatcher.IsDisposed) return;
        try { dispatcher.BeginInvoke(new Action(() => { if (!closing) action(); })); } catch (InvalidOperationException) { }
    }
    private void Pause(int minutes) { pauseUntil = clock.Elapsed.TotalSeconds + minutes * 60; dimmer.Reset(); Apply(0); UpdateStatus(); }
    private void Update()
    {
        try
        {
            var now = clock.Elapsed.TotalSeconds;
            var activity = sample is not null && now - receivedAt < 5 ? sample.Activity : null;
            Apply(dimmer.Evaluate(now, activity, suspended || settingsForm is not null || now < pauseUntil));
            UpdateStatus();
        }
        catch (Exception) { dimmer.Reset(); sample = null; ClearOverlays(); connectionStatus = "画面制御を停止しました — 設定を保存すると再試行します"; }
    }
    private void UpdateStatus()
    {
        var remaining = pauseUntil - clock.Elapsed.TotalSeconds;
        var text = remaining > 0 ? $"停止中 — 残り{Math.Ceiling(remaining / 60):0}分" : connectionStatus;
        stateItem.Text = text;
        tray.Text = text.Length <= 63 ? text : text[..63];
        var icon = remaining > 0 ? pausedIcon : runningIcon;
        if (tray.Icon != icon) tray.Icon = icon;
    }
    private void Apply(double opacity)
    {
        if (Math.Abs(appliedOpacity - opacity) < 1.0 / 255 && !(opacity == 0 && appliedOpacity != 0)) return;
        foreach (var overlay in overlays) overlay.SetDim(opacity);
        appliedOpacity = opacity;
    }
    private void ClearOverlays() { foreach (var overlay in overlays) overlay.Dispose(); overlays.Clear(); appliedOpacity = -1; }
    private void RebuildOverlays() { ClearOverlays(); dimmer.Reset(); foreach (var screen in Screen.AllScreens) overlays.Add(new(screen.Bounds)); }
    private void DisplayChanged(object? sender, EventArgs args) => Post(() => { sample = null; RebuildOverlays(); });
    private void PowerChanged(object sender, PowerModeChangedEventArgs args) => Post(() =>
    {
        suspended = args.Mode == PowerModes.Suspend;
        sample = null; dimmer.Reset(); Apply(0); client.Reconfigure();
    });
    private void OpenSettings()
    {
        if (settingsForm is not null) { settingsForm.Activate(); return; }
        dimmer.Reset(); Apply(0);
        settingsForm = new(client, () => settings, updated =>
        {
            updated.Save(); Volatile.Write(ref settings, updated); sample = null;
            dimmer = new(updated.Dimmer); RebuildOverlays(); client.Reconfigure();
        });
        settingsForm.FormClosed += (_, _) => { settingsForm = null; dimmer.Reset(); };
        settingsForm.Show();
    }
    protected override void ExitThreadCore()
    {
        closing = true; timer.Stop(); client.Dispose(); ClearOverlays(); settingsForm?.Close();
        tray.Visible = false; tray.Dispose(); timer.Dispose(); menu.Dispose(); dispatcher.Dispose();
        runningIcon.Dispose(); pausedIcon.Dispose();
        SystemEvents.DisplaySettingsChanged -= DisplayChanged; SystemEvents.PowerModeChanged -= PowerChanged;
        base.ExitThreadCore();
    }
    private static Icon MakeIcon(Color color)
    {
        using var bitmap = new Bitmap(32, 32);
        using var graphics = Graphics.FromImage(bitmap);
        graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
        using var pen = new Pen(color, 2.5f);
        graphics.DrawEllipse(pen, 2, 17, 12, 12); graphics.DrawEllipse(pen, 18, 17, 12, 12);
        graphics.DrawLines(pen, [new Point(8, 23), new Point(14, 12), new Point(22, 23), new Point(8, 23), new Point(19, 14), new Point(24, 23)]);
        graphics.DrawLine(pen, 11, 11, 17, 11); graphics.DrawLine(pen, 19, 14, 19, 7); graphics.DrawLine(pen, 19, 7, 24, 7);
        var handle = bitmap.GetHicon();
        try { using var borrowed = Icon.FromHandle(handle); return (Icon)borrowed.Clone(); } finally { DestroyIcon(handle); }
    }
    [DllImport("user32.dll")][return: MarshalAs(UnmanagedType.Bool)] private static extern bool DestroyIcon(IntPtr handle);
}
