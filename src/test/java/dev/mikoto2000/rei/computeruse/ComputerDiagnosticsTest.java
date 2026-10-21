package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 画面操作診断情報の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ComputerDiagnosticsTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 画面操作の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void storesOriginalImagesOverlayAndMappedCoordinatesWithoutChangingModelImage() throws Exception {
    var diagnostics = new ComputerDiagnostics(directory);
    var run = diagnostics.begin();
    var screen = MultiDisplayTest.desktop();
    diagnostics.observed(run, 1, screen);
    var action =
        new ComputerAction.Click(
            new ComputerAction.Target("left", 400, 200, "private label"),
            .9,
            ComputerAction.Risk.LOW);
    diagnostics.action(run, 1, screen, action, "decided");
    diagnostics.action(run, 1, screen, action, "dispatched");
    var step = run.resolve("step-001");
    assertEquals(1600, ImageIO.read(step.resolve("display-2.png").toFile()).getWidth());
    var marker = ImageIO.read(step.resolve("target.png").toFile());
    assertNotEquals(screen.displays().get(1).image().getRGB(400, 200), marker.getRGB(400, 200));
    var json =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(step.resolve("dispatched.json").toFile());
    assertEquals(-600, json.get("robotX").asInt());
    assertEquals(100, json.get("robotY").asInt());
    assertEquals("left", json.get("displayId").asText());
    assertFalse(Files.readString(step.resolve("dispatched.json")).contains("private label"));
  }

  /** 画面操作の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledDoesNotCreateFiles() throws Exception {
    var diagnostics = new ComputerDiagnostics(null);
    var run = diagnostics.begin();
    diagnostics.observed(run, 1, MultiDisplayTest.desktop());
    assertNull(run);
    try (var files = Files.list(directory)) {
      assertEquals(0, files.count());
    }
  }

  /** サービスについて、観測を書き込むことを確認する。 */
  @Test
  void serviceWritesObservedAndDispatchedArtifactsAndAnnouncesLocation() throws Exception {
    var events = new java.util.ArrayList<ComputerProgress>();
    var service =
        new ComputerUseService(
            MultiDisplayTest::desktop,
            o ->
                o.step() == 1
                    ? new ComputerAction.Click(
                        new ComputerAction.Target("left", 400, 200, "editor"),
                        .9,
                        ComputerAction.Risk.LOW)
                    : new ComputerAction.Done("visible"),
            (a, s) -> {},
            a -> {},
            SafetyPolicy.lowRiskOnly(),
            () -> false,
            events::add,
            20,
            5,
            new ComputerDiagnostics(directory));
    assertEquals(ComputerUseResult.Status.DONE, service.run("goal").status());
    assertTrue(
        events.stream()
            .anyMatch(
                e -> e.phase().equals("diagnostics") && e.reason().contains(directory.toString())));
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e.phase().equals("input_coordinates")
                        && e.reason().contains("robot=(-600,100)")));
    try (var files = Files.walk(directory)) {
      assertEquals(
          1, files.filter(p -> p.getFileName().toString().equals("dispatched.json")).count());
    }
  }

  /** 画面操作の支援で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void artifactFailureDoesNotRetryOrPreventInput() throws Exception {
    Path file = Files.writeString(directory.resolve("file"), "occupied");
    var inputs = new java.util.ArrayList<ComputerAction>();
    var service =
        new ComputerUseService(
            MultiDisplayTest::desktop,
            o ->
                o.step() == 1
                    ? new ComputerAction.PressKey("TAB", ComputerAction.Risk.LOW)
                    : new ComputerAction.Done("visible"),
            (a, s) -> inputs.add(a),
            a -> {},
            SafetyPolicy.lowRiskOnly(),
            () -> false,
            p -> {},
            20,
            5,
            new ComputerDiagnostics(file));
    assertEquals(ComputerUseResult.Status.DONE, service.run("goal").status());
    assertEquals(1, inputs.size());
  }
}
