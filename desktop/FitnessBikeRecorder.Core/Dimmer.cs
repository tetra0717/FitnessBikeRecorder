namespace FitnessBikeRecorder.Core;

public sealed record DimmerSettings(double GraceSeconds = 5, double FadeSeconds = 10, double MaximumOpacity = 0.8);

// All times are elapsed monotonic time, never the wall clock.
public sealed class Dimmer(DimmerSettings settings)
{
    private double? idleSince;
    private double? resumeSince;
    private double fadeFrom;
    private double resumeFrom;
    public double Opacity { get; private set; }

    public double Evaluate(double now, bool? isActive, bool suppressed = false)
    {
        if (suppressed || isActive is null)
        {
            Reset();
            return 0;
        }
        if (isActive.Value)
        {
            idleSince = null;
            if (resumeSince is null) { resumeSince = now; resumeFrom = Opacity; }
            Opacity = resumeFrom * (1 - Smooth(now - resumeSince.Value));
        }
        else
        {
            resumeSince = null;
            if (idleSince is null) { idleSince = now; fadeFrom = Opacity; }
            var progress = (now - idleSince.Value - settings.GraceSeconds) / settings.FadeSeconds;
            Opacity = fadeFrom + (settings.MaximumOpacity - fadeFrom) * Smooth(progress);
        }
        return Math.Clamp(Opacity, 0, settings.MaximumOpacity);
    }

    public void Reset() { idleSince = null; resumeSince = null; Opacity = 0; }
    private static double Smooth(double t) { t = Math.Clamp(t, 0, 1); return t * t * (3 - 2 * t); }
}
