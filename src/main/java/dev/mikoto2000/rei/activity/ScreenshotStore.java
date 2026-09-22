package dev.mikoto2000.rei.activity;
import dev.mikoto2000.rei.computeruse.CapturedScreen;
import java.time.Instant;
import java.util.List;
public interface ScreenshotStore {
  List<String> save(String id, Instant capturedAt, CapturedScreen screen) throws Exception;
  void cleanup(Instant before) throws Exception;
}
