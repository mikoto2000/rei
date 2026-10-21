package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.AwtRobotDriver;
import dev.mikoto2000.rei.computeruse.CapturedScreen;
import dev.mikoto2000.rei.computeruse.DisplayCapture;
import dev.mikoto2000.rei.computeruse.RobotScreenCapture;
import dev.mikoto2000.rei.computeruse.ScreenCapture;
import dev.mikoto2000.rei.computeruse.ScreenGeometry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Windowsデスクトップ作業状況の状況を観測し、記録や判断に必要な情報を提供する。
 *
 * <p>Read-only foreground/visible Win32 metadata probes; pixels use the existing multi-display
 * Robot adapter.
 */
public final class WindowsDesktopActivityObserver implements DesktopActivityObserver {
  /** 作業状況の把握で用いるlightweightを返す。 */
  @Override
  public Lightweight lightweight() {
    return WindowsInputProbe.read();
  }

  /** この機能の動作設定。 */
  private final ActivityProperties properties;

  /** 直近付随情報。 */
  private volatile Metadata lastMetadata;

  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WindowsDesktopActivityObserver() {
    this(null);
  }

  /** 指定された値や利用先を使って、WindowsDesktopActivityObserverを初期化する。 */
  public WindowsDesktopActivityObserver(ActivityProperties properties) {
    this.properties = properties;
  }

  /** 取得記録。 */
  private final ScreenCapture capture = new RobotScreenCapture(new AwtRobotDriver());

  /** 後から状況を確認できるよう、Windowsデスクトップ作業状況観測を記録する。 */
  @Override
  public CapturedScreen capture() throws Exception {
    var metadata = lastMetadata;
    boolean identified =
        properties != null && properties.getDetection().isBackgroundFullScreenEnabled();
    if (identified && (metadata == null || !metadata.complete()))
      throw new IllegalStateException("Desktop identity unavailable");
    var screen = capture.captureScreen();
    return identified ? identify(screen, metadata.monitors()) : screen;
  }

  /** 作業状況の把握で用いるidentifyを返す。 */
  static CapturedScreen identify(CapturedScreen screen, java.util.List<MonitorIdentity> monitors) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (monitors == null || monitors.isEmpty() || monitors.size() > 16)
      throw new IllegalStateException("Desktop identity unavailable");
    var mapped = new java.util.ArrayList<DisplayCapture>();
    for (var display : screen.displays()) {
      var g = display.geometry();
      var b = ActivityImages.physicalBounds(g);
      var matches =
          monitors.stream()
              .filter(
                  m ->
                      m != null
                          && m.bounds() != null
                          && m.id() != null
                          && !m.id().isBlank()
                          && m.bounds().x() == b.x
                          && m.bounds().y() == b.y
                          && m.bounds().width() == b.width
                          && m.bounds().height() == b.height)
              .toList();
      if (matches.size() != 1) throw new IllegalStateException("Desktop identity ambiguous");
      mapped.add(
          new DisplayCapture(
              new ScreenGeometry(
                  matches.getFirst().id(),
                  g.bounds(),
                  g.virtualBounds(),
                  g.primary(),
                  g.scaleX(),
                  g.scaleY()),
              display.image()));
    }
    if (mapped.stream().map(d -> d.geometry().id()).distinct().count() != mapped.size())
      throw new IllegalStateException("Desktop identity duplicated");
    return new CapturedScreen(mapped);
  }

  /** 作業状況の把握で用いる付随情報を返す。 */
  @Override
  public Metadata metadata() throws Exception {
    var node = probe("metadata.ps1", 1048576);
    if (node == null) return null;
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var result = mapper.treeToValue(node, Metadata.class);
    lastMetadata = result;
    return result;
  }

  /** 作業状況の把握で用いる前面を返す。 */
  @Override
  public ForegroundWindow foreground() throws Exception {
    var node = probe("foreground.ps1", 32768);
    if (node == null
        || !node.path("processName").isTextual()
        || !node.path("windowTitle").isTextual()
        || !node.path("windowId").isTextual()
        || !node.path("processId").isIntegralNumber()) return null;
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .treeToValue(node, ForegroundWindow.class);
  }

  /** 作業状況の把握で用いる利用可否の確認を返す。 */
  private com.fasterxml.jackson.databind.JsonNode probe(String resource, long maxBytes)
      throws Exception {
    if (!System.getProperty("os.name", "").startsWith("Windows")) return null;
    String script;
    try (var in = getClass().getResourceAsStream("/activity/" + resource)) {
      script =
          new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
    }
    Path output = Files.createTempFile("rei-activity-foreground-", ".json");
    Process process = null;
    try {
      var executable =
          Path.of(
              System.getenv("SystemRoot"),
              "System32",
              "WindowsPowerShell",
              "v1.0",
              "powershell.exe");
      process =
          new ProcessBuilder(
                  executable.toString(),
                  "-NoProfile",
                  "-NonInteractive",
                  "-WindowStyle",
                  "Hidden",
                  "-EncodedCommand",
                  Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
              .redirectOutput(output.toFile())
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(5, TimeUnit.SECONDS)
          || process.exitValue() != 0
          || Files.size(output) > maxBytes) return null;
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      return mapper.readTree(Files.readString(output, StandardCharsets.UTF_8).strip());
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw e;
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
      Files.deleteIfExists(output);
    }
  }
}
