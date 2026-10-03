using System.Net;
using FitnessBikeRecorder.Core;

namespace FitnessBikeRecorder.PC;

internal sealed class SettingsForm : Form
{
    private readonly ComboBox phones = new() { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
    private readonly TextBox code = new() { Dock = DockStyle.Fill, PlaceholderText = "スマホの「PC連携」に表示された接続コード", UseSystemPasswordChar = true };
    private readonly NumericUpDown grace = new() { Minimum = 0, Maximum = 300, Dock = DockStyle.Fill };
    private readonly NumericUpDown fade = new() { Minimum = 1, Maximum = 300, Dock = DockStyle.Fill };
    private readonly NumericUpDown maximum = new() { Minimum = 10, Maximum = 90, Dock = DockStyle.Fill };
    private readonly TextBox manualId = new() { Dock = DockStyle.Fill, PlaceholderText = "通常は空欄" };
    private readonly TextBox manualEndpoint = new() { Dock = DockStyle.Fill, PlaceholderText = "通常は空欄（例：192.168.1.20:12345）" };
    private readonly Label status = new() { AutoSize = true, Dock = DockStyle.Fill, MaximumSize = new Size(500, 0) };
    private readonly System.Windows.Forms.Timer refresh = new() { Interval = 2000 };
    private readonly PhoneClient client;
    private readonly Func<AppSettings> getSettings;
    private readonly Action<AppSettings> save;

    public SettingsForm(PhoneClient client, Func<AppSettings> getSettings, Action<AppSettings> save)
    {
        this.client = client; this.getSettings = getSettings; this.save = save;
        Text = "Fitness Bike Recorder — 設定";
        Font = new Font("Yu Gothic UI", 9);
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(650, 560);
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        var table = new TableLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, Padding = new Padding(20), ColumnCount = 2, RowCount = 11 };
        table.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 210));
        table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (var row = 0; row < 11; row++) table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        Controls.Add(table);
        var instruction = new Label { Text = "スマホと同じWi-Fiに接続し、FBRecoの「PC連携」を開いてください。\n登録後は、次回から自動で接続します。", AutoSize = true, Dock = DockStyle.Fill };
        table.Controls.Add(instruction, 0, 0); table.SetColumnSpan(instruction, 2);
        Row(table, 1, "スマホ", phones);
        Row(table, 2, "接続コード", code);
        Row(table, 3, "暗くなるまで待つ（秒）", grace);
        Row(table, 4, "暗くする時間（秒）", fade);
        Row(table, 5, "最大の暗さ（％）", maximum);
        var note = new Label { Text = "漕ぎ始めると約1秒で戻ります。設定を開いている間は暗転しません。", AutoSize = true, Dock = DockStyle.Fill };
        table.Controls.Add(note, 0, 6); table.SetColumnSpan(note, 2);
        Row(table, 7, "手動接続：端末ID", manualId);
        Row(table, 8, "手動接続：IP:ポート", manualEndpoint);
        table.Controls.Add(status, 0, 9); table.SetColumnSpan(status, 2);
        var buttons = new FlowLayoutPanel { FlowDirection = FlowDirection.RightToLeft, AutoSize = true, Dock = DockStyle.Fill };
        var apply = new Button { Text = "保存", AutoSize = true };
        var cancel = new Button { Text = "閉じる", AutoSize = true, DialogResult = DialogResult.Cancel };
        var forget = new Button { Text = "登録を解除", AutoSize = true };
        buttons.Controls.AddRange([apply, cancel, forget]);
        table.Controls.Add(buttons, 0, 10); table.SetColumnSpan(buttons, 2);
        AcceptButton = apply; CancelButton = cancel;
        var settings = getSettings();
        grace.Value = (decimal)settings.GraceSeconds; fade.Value = (decimal)settings.FadeSeconds;
        maximum.Value = (decimal)(settings.MaximumOpacity * 100);
        if (settings.ManualHost is not null) { manualId.Text = settings.DeviceId; manualEndpoint.Text = $"{settings.ManualHost}:{settings.ManualPort}"; }
        apply.Click += (_, _) => SaveSettings();
        forget.Click += (_, _) => { try { save(getSettings() with { DeviceId = null, ProtectedCode = null, ManualHost = null, ManualPort = 0 }); Close(); } catch (Exception ex) { status.Text = ex.Message; } };
        refresh.Tick += (_, _) => RefreshPhones();
        FormClosed += (_, _) => refresh.Dispose();
        RefreshPhones(); refresh.Start(); client.Refresh();
    }

    private static void Row(TableLayoutPanel table, int row, string label, Control control)
    {
        table.Controls.Add(new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left }, 0, row);
        table.Controls.Add(control, 1, row);
    }
    private void RefreshPhones()
    {
        var selectedId = (phones.SelectedItem as PhoneEndpoint)?.DeviceId ?? getSettings().DeviceId;
        var discovered = client.Phones.ToList();
        if (getSettings().DeviceId is { } registered && !discovered.Any(p => p.DeviceId == registered))
            discovered.Add(new(registered, "登録済み（現在未検出）", IPAddress.Any, 0));
        var selected = discovered.FindIndex(p => p.DeviceId == selectedId);
        phones.BeginUpdate(); phones.Items.Clear(); phones.Items.AddRange(discovered.Cast<object>().ToArray());
        phones.SelectedIndex = selected >= 0 ? selected : discovered.Count == 1 ? 0 : -1;
        phones.EndUpdate();
        if (discovered.Count == 0) status.Text = "スマホを検索中。検出できない場合はスマホの端末IDと接続先を入力できます。";
    }
    private void SaveSettings()
    {
        try
        {
            var previous = getSettings();
            var id = string.IsNullOrWhiteSpace(manualId.Text) ? (phones.SelectedItem as PhoneEndpoint)?.DeviceId : manualId.Text.Trim();
            if (!Guid.TryParse(id, out var guid)) throw new FormatException("スマホを選択してください。手動接続では端末IDを入力してください。");
            id = guid.ToString();
            var protectedCode = string.IsNullOrWhiteSpace(code.Text) && id == previous.DeviceId
                ? previous.ProtectedCode : AppSettings.Protect(Protocol.ParseCode(code.Text));
            if (protectedCode is null) throw new FormatException("接続コードを入力してください。");
            string? host = null; var port = 0;
            if (!string.IsNullOrWhiteSpace(manualEndpoint.Text))
            {
                if (!IPEndPoint.TryParse(manualEndpoint.Text.Trim(), out var endpoint) || endpoint.Port == 0)
                    throw new FormatException("接続先はIPアドレス:ポートの形式で入力してください。");
                host = endpoint.Address.ToString(); port = endpoint.Port;
            }
            save(previous with { DeviceId = id, ProtectedCode = protectedCode, GraceSeconds = (double)grace.Value,
                FadeSeconds = (double)fade.Value, MaximumOpacity = (double)maximum.Value / 100, ManualHost = host, ManualPort = port });
            Close();
        }
        catch (Exception ex) { status.Text = ex.Message; }
    }
}
