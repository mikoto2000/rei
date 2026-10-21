package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 表示表示画面操作画像認識モデルの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ShowUiComputerVisionModelTest {
  /** 検証に使う診断情報。 */
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path diagnostics;

  /** 検証用に、指定された診断情報を反映した情報を作成する。 */
  private ComputerObservation withDiagnostics(java.nio.file.Path path) {
    var o = observation();
    return new ComputerObservation(
        o.goal(), o.screenshot(), o.recentHistory(), o.step(), o.maxSteps(), path);
  }

  /** 診断情報について、送信バイト数を保持することを確認する。 */
  @Test
  void diagnosticsPreserveSentBytesAndInvalidResponse() throws Exception {
    var grounding = mock(ChatModel.class);
    when(grounding.call(any(Prompt.class)))
        .thenReturn(SpringAiComputerVisionModelTest.response("oops\n[2,3]"));
    var model =
        TestGroundingModels.create(
            o -> click(), grounding, OpenAiChatOptions::builder, () -> false);
    assertThrows(InvalidComputerDecision.class, () -> model.decide(withDiagnostics(diagnostics)));
    var prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(grounding).call(prompt.capture());
    var user = (UserMessage) prompt.getValue().getInstructions().getFirst();
    var step = diagnostics.resolve("step-001");
    assertArrayEquals(
        user.getMedia().getFirst().getDataAsByteArray(),
        java.nio.file.Files.readAllBytes(step.resolve("showui-input.png")));
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var request = mapper.readTree(step.resolve("showui-request.json").toFile());
    assertEquals(user.getText(), request.get("prompt").asText());
    assertEquals("Post button", request.get("targetDescription").asText());
    assertEquals("left", request.get("displayId").asText());
    assertEquals(3840, request.get("sourceWidth").asInt());
    assertEquals(
        "oops\n[2,3]",
        mapper
            .readTree(step.resolve("showui-response.json").toFile())
            .get("texts")
            .get(0)
            .asText());
  }

  /** 画面操作の支援で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void recordsTransportExceptionAndDiagnosticFailureDoesNotPreventInference() throws Exception {
    var grounding = mock(ChatModel.class);
    when(grounding.call(any(Prompt.class)))
        .thenThrow(new IllegalStateException("test transport failure"));
    var model =
        TestGroundingModels.create(
            o -> click(), grounding, OpenAiChatOptions::builder, () -> false);
    assertThrows(IllegalStateException.class, () -> model.decide(withDiagnostics(diagnostics)));
    assertTrue(
        java.nio.file.Files.readString(diagnostics.resolve("step-001/showui-error.txt"))
            .contains("test transport failure"));
    var blocked = diagnostics.resolve("file");
    java.nio.file.Files.writeString(blocked, "not a directory");
    doReturn(SpringAiComputerVisionModelTest.response("[0.5,0.5]"))
        .when(grounding)
        .call(any(Prompt.class));
    assertInstanceOf(ComputerAction.Click.class, model.decide(withDiagnostics(blocked)));
  }

  /** {@code observation} の条件を再現するための補助情報を返す。 */
  private ComputerObservation observation() {
    var virtual = new Rectangle(-3840, 0, 7680, 2160);
    return new ComputerObservation(
        "private goal",
        new CapturedScreen(
            List.of(
                new DisplayCapture(
                    new ScreenGeometry(
                        "left", new Rectangle(-3840, 0, 3840, 2160), virtual, false, 1, 1),
                    new BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB)),
                new DisplayCapture(
                    new ScreenGeometry(
                        "right", new Rectangle(0, 0, 3840, 2160), virtual, true, 1, 1),
                    new BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB)))),
        List.of("private history"),
        1,
        20);
  }

  /** {@code click} の条件を再現するための補助情報を返す。 */
  private ComputerAction click() {
    return new ComputerAction.Click(
        new ComputerAction.Target("left", 10, 10, "Post button"), .9, ComputerAction.Risk.LOW);
  }

  /** 画面操作の支援で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void sendsOnlySelectedBoundedImageAndMapsNormalizedPointToOriginalDisplay() throws Exception {
    var grounding = mock(ChatModel.class);
    when(grounding.call(any(Prompt.class)))
        .thenReturn(
            SpringAiComputerVisionModelTest.response("[0.75,0.25]"),
            SpringAiComputerVisionModelTest.response("[0.5,0.5]"));
    var model =
        TestGroundingModels.create(
            o -> {
              assertTrue(
                  o.screenshot().displays().stream()
                      .allMatch(
                          d ->
                              (long) d.image().getWidth() * d.image().getHeight()
                                  <= ShowUiComputerVisionModel.MAX_PIXELS));
              return click();
            },
            grounding,
            OpenAiChatOptions::builder,
            () -> false);
    var observation = observation();
    var action = (ComputerAction.Click) model.decide(observation);
    assertEquals(2880, action.target().x());
    assertEquals(540, action.target().y());
    assertEquals(-960, observation.screenshot().display("left").desktopPoint(action.target()).x);
    var captured = ArgumentCaptor.forClass(Prompt.class);
    verify(grounding, times(2)).call(captured.capture());
    var prompt = captured.getValue();
    assertEquals(1, prompt.getInstructions().size());
    var user = (UserMessage) prompt.getInstructions().getFirst();
    assertEquals(1, user.getMedia().size());
    var image =
        javax.imageio.ImageIO.read(
            new java.io.ByteArrayInputStream(user.getMedia().getFirst().getDataAsByteArray()));
    assertTrue((long) image.getWidth() * image.getHeight() <= ShowUiComputerVisionModel.MAX_PIXELS);
    assertTrue(user.getText().contains("Post button"));
    assertFalse(user.getText().contains("private"));
    var options = (OpenAiChatOptions) prompt.getOptions();
    assertEquals(128, options.getMaxTokens());
    assertNull(options.getResponseFormat());
    assertTrue(options.getToolCallbacks().isEmpty());
  }

  /** 画面操作の支援で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidGroundingFailsTaskWithoutExecutingPlannerCoordinates() {
    var grounding = mock(ChatModel.class);
    when(grounding.call(any(Prompt.class)))
        .thenReturn(SpringAiComputerVisionModelTest.response("[1.1,0.2]"));
    var model =
        TestGroundingModels.create(
            o -> click(), grounding, OpenAiChatOptions::builder, () -> false);
    var service =
        new ComputerUseService(
            () -> observation().screenshot(),
            model,
            (a, s) -> fail("must not click"),
            a -> {},
            SafetyPolicy.lowRiskOnly(),
            () -> false,
            p -> {},
            2,
            2);
    assertEquals(ComputerUseResult.Status.MODEL_ERROR, service.run("goal").status());
    verify(grounding).call(any(Prompt.class));
  }

  /** 画面操作の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void nonClickDoesNotCallGrounding() throws Exception {
    var grounding = mock(ChatModel.class);
    var model =
        TestGroundingModels.create(
            o -> new ComputerAction.Done("visible"),
            grounding,
            OpenAiChatOptions::builder,
            () -> false);
    assertInstanceOf(ComputerAction.Done.class, model.decide(observation()));
    verify(grounding, never()).call(any(Prompt.class));
  }

  /** 画面操作の支援で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void transportFailurePropagatesWithoutRetry() {
    var grounding = mock(ChatModel.class);
    var error = new IllegalStateException("server unavailable");
    when(grounding.call(any(Prompt.class))).thenThrow(error);
    var model =
        TestGroundingModels.create(
            o -> click(), grounding, OpenAiChatOptions::builder, () -> false);
    assertSame(error, assertThrows(IllegalStateException.class, () -> model.decide(observation())));
    verify(grounding).call(any(Prompt.class));
  }

  /** 画面操作の支援の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAfterPlanningPreventsGrounding() {
    var grounding = mock(ChatModel.class);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var model =
        TestGroundingModels.create(
            o -> {
              cancelled.set(true);
              return click();
            },
            grounding,
            OpenAiChatOptions::builder,
            cancelled::get);
    assertThrows(
        java.util.concurrent.CancellationException.class, () -> model.decide(observation()));
    verify(grounding, never()).call(any(Prompt.class));
  }

  /** 形式が不正なを受け付けないことを確認する。 */
  @Test
  void rejectsMalformedAndOutOfRangeCoordinates() {
    for (String text :
        List.of("[1,2]", "[0,0] trailing", "[\"0.5\",0.5]", "[0.5]", "{}", "[0,0,0]"))
      assertThrows(InvalidComputerDecision.class, () -> ShowUiComputerVisionModel.parse(text));
    assertArrayEquals(new double[] {0, 1}, ShowUiComputerVisionModel.parse("[0,1]"));
  }
}
