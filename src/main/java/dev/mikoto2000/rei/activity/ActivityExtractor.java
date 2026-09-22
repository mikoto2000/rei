package dev.mikoto2000.rei.activity;
import dev.mikoto2000.rei.computeruse.CapturedScreen;
public interface ActivityExtractor {
  Result extract(CapturedScreen screen, ForegroundWindow foreground) throws Exception;
  record Result(ActivityRecord.Inference inference, double confidence) {}
}
