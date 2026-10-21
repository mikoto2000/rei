package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.Clipboard;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 複数表示の結果と状態の契約を検証するテスト。 */
class MultiDisplayTest {
  /** 画面操作の支援で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void mapsFractionalDpiAndNegativeVerticalOrigins() {
    var frame =
        new DisplayCapture(
            new ScreenGeometry(
                "above",
                new Rectangle(0, -600, 800, 600),
                new Rectangle(0, -600, 800, 1200),
                false,
                1.5,
                1.5),
            new BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB));
    assertEquals(
        new Point(200, -500),
        frame.desktopPoint(new ComputerAction.Target("above", 300, 150, "target")));
  }

  /** {@code left} の条件を再現するための補助情報を返す。 */
  static ScreenGeometry left() {
    return new ScreenGeometry(
        "left", new Rectangle(-800, 0, 800, 600), new Rectangle(-800, 0, 1600, 600), false, 2, 2);
  }

  /** 検証用に、画面操作の支援の起動要求を受け付け、実行を開始する。 */
  static ScreenGeometry main() {
    return new ScreenGeometry(
        "main", new Rectangle(0, 0, 800, 600), new Rectangle(-800, 0, 1600, 600), true, 1, 1);
  }

  /** {@code desktop} の条件を再現するための補助情報を返す。 */
  static CapturedScreen desktop() {
    return new CapturedScreen(
        List.of(
            new DisplayCapture(main(), new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)),
            new DisplayCapture(left(), new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB))));
  }

  /** 画面操作の支援で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void routesImageCoordinatesToSelectedMonitorWithMixedScaling() throws Exception {
    var driver = mock(RobotDriver.class);
    when(driver.displays()).thenReturn(List.of(main(), left()));
    var input =
        new RobotComputerInput(
            driver, new ClipboardPaste(() -> new Clipboard("test"), ms -> {}, 1), ms -> {});
    input.execute(
        new ComputerAction.Click(
            new ComputerAction.Target("left", 400, 200, "editor"), .9, ComputerAction.Risk.LOW),
        desktop());
    var order = inOrder(driver);
    order.verify(driver).selectDisplay(left());
    order.verify(driver).move(-600, 100);
  }

  /** 画面操作の支援で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectsAmbiguousTargetsAndChangedDpiBeforeInput() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ActionValidator.validate(
                new ComputerAction.Click(
                    new ComputerAction.Target(10, 10, "editor"), .9, ComputerAction.Risk.LOW),
                desktop()));
    var driver = mock(RobotDriver.class);
    when(driver.displays())
        .thenReturn(
            List.of(
                main(),
                new ScreenGeometry(
                    "left", left().bounds(), left().virtualBounds(), false, 1.5, 1.5)));
    var input =
        new RobotComputerInput(
            driver, new ClipboardPaste(() -> new Clipboard("test"), ms -> {}, 1), ms -> {});
    assertThrows(
        IllegalArgumentException.class,
        () ->
            input.execute(
                new ComputerAction.Click(
                    new ComputerAction.Target("left", 10, 10, "editor"),
                    .9,
                    ComputerAction.Risk.LOW),
                desktop()));
    verify(driver, never()).move(anyInt(), anyInt());
  }

  /** 画面操作の支援で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void capturesEachDisplayAndRejectsTopologyChangesDuringCapture() throws Exception {
    var driver = mock(RobotDriver.class);
    when(driver.displays()).thenReturn(List.of(main(), left()));
    when(driver.capture(main().bounds())).thenReturn(desktop().displays().get(0).image());
    when(driver.capture(left().bounds())).thenReturn(desktop().displays().get(1).image());
    assertEquals(2, new RobotScreenCapture(driver).captureScreen().displays().size());
    when(driver.displays()).thenReturn(List.of(main(), left()), List.of(main()));
    assertThrows(IllegalStateException.class, () -> new RobotScreenCapture(driver).captureScreen());
  }

  /** 入力の解釈について、選択中画像許容範囲を利用することを確認する。 */
  @Test
  void parserUsesSelectedImageBoundsAndRejectsUnknownDisplay() {
    String json =
        ActionValidationTest.json(
                "CLICK",
                "target",
                "{\"displayId\":\"left\",\"description\":\"editor\",\"centerX\":0.75,\"centerY\":0.5}")
            .replace("\"confidence\":null", "\"confidence\":0.9");
    var click = (ComputerAction.Click) new ActionParser().parse(json, desktop());
    assertEquals("left", click.target().displayId());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ActionParser().parse(json.replace("left", "unknown"), desktop()));
  }

  /** 画面操作の支援で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void visionReceivesAllImagesWithUnambiguousLabels() throws Exception {
    var model = mock(org.springframework.ai.chat.model.ChatModel.class);
    when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(
            SpringAiComputerVisionModelTest.response(
                ActionValidationTest.json("DONE", "reason", "\"visible\"")));
    new SpringAiComputerVisionModel(
            model, org.springframework.ai.openai.OpenAiChatOptions::builder, () -> false, 0)
        .decide(new ComputerObservation("goal", desktop(), List.of(), 1, 20));
    var prompt =
        org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
    verify(model).call(prompt.capture());
    var user =
        (org.springframework.ai.chat.messages.UserMessage)
            prompt.getValue().getInstructions().getLast();
    assertEquals(2, user.getMedia().size());
    assertTrue(user.getText().contains("displayId=left"));
    assertTrue(user.getText().contains("1600x1200"));
  }
}
