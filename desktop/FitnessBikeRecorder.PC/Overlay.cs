using System.Runtime.InteropServices;

namespace FitnessBikeRecorder.PC;

internal sealed class Overlay : Form
{
    private const int ExLayered = 0x80000, ExTransparent = 0x20, ExNoActivate = 0x8000000, ExToolWindow = 0x80;
    public Overlay(Rectangle bounds)
    {
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        BackColor = Color.Black;
        StartPosition = FormStartPosition.Manual;
        Bounds = bounds;
        TopMost = true;
        AutoScaleMode = AutoScaleMode.None;
    }
    protected override bool ShowWithoutActivation => true;
    protected override CreateParams CreateParams
    {
        get { var parameters = base.CreateParams; parameters.ExStyle |= ExLayered | ExTransparent | ExNoActivate | ExToolWindow; return parameters; }
    }
    protected override void WndProc(ref Message message)
    {
        if (message.Msg == 0x21) { message.Result = 3; return; } // MA_NOACTIVATE
        base.WndProc(ref message);
    }
    public void SetDim(double opacity)
    {
        if (opacity <= 0) { if (Visible) Hide(); return; }
        var alpha = (byte)Math.Round(Math.Clamp(opacity, 0, 0.9) * 255);
        // Set alpha before showing to avoid a black flash and preserve click-through styles.
        if (!SetLayeredWindowAttributes(Handle, 0, alpha, 2))
            throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        if (!Visible) Show();
        SetWindowPos(Handle, new IntPtr(-1), 0, 0, 0, 0, 0x1 | 0x2 | 0x10); // no activate
    }
    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetLayeredWindowAttributes(IntPtr hwnd, uint color, byte alpha, uint flags);
    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int width, int height, uint flags);
}
