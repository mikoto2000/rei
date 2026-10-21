package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 作業状況画像認識倍率の結果と状態の契約を検証するテスト。 */
class ActivityVisionScaleTest {
  /** 作業状況の把握で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void activityHasIndependentSmallCompletionBudget() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage(ActivityExtractionTest.VALID)))));
    new VisionActivityExtractor(
            () -> model, () -> OpenAiChatOptions.builder().maxTokens(32768).build())
        .extract(
            ActivityCaptureTest.screen(20),
            new ForegroundWindow("Firefox", 1, "Browser", "window"));
    var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
    verify(model).call(prompt.capture());
    var options = (OpenAiChatOptions) prompt.getValue().getOptions();
    assertEquals(2048, options.getMaxCompletionTokens());
    assertNull(options.getMaxTokens());
  }

  /** 検証用に、受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  private BufferedImage decode(byte[] bytes) throws Exception {
    return ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)));
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void halfScaleReducesBothDimensionsWithoutChangingOriginal() throws Exception {
    var image = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
    image.setRGB(20, 20, 0xff336699);
    var result = decode(PngScreenshotEncoder.encode(image, .5));
    assertEquals(960, result.getWidth());
    assertEquals(540, result.getHeight());
    assertEquals(1920, image.getWidth());
    assertEquals(0xff336699, image.getRGB(20, 20));
  }

  /** 全体倍率について、元の画素数を保持することを確認する。 */
  @Test
  void fullScaleRetainsOriginalPixelsAndTinyImagesStayValid() throws Exception {
    var image = ActivityPolicyTest.image(137);
    var full = decode(PngScreenshotEncoder.encode(image, 1));
    assertEquals(image.getWidth(), full.getWidth());
    assertEquals(image.getRGB(10, 10), full.getRGB(10, 10));
    var tiny =
        decode(
            PngScreenshotEncoder.encode(new BufferedImage(1, 3, BufferedImage.TYPE_INT_RGB), .5));
    assertEquals(1, tiny.getWidth());
    assertEquals(2, tiny.getHeight());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidScaleIsRejectedBySettingsAndEncoder() {
    for (double scale : new double[] {0, -1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
      var p = new ActivityProperties();
      p.setVisionImageScale(scale);
      assertThrows(IllegalArgumentException.class, p::validate);
      assertThrows(
          IllegalArgumentException.class,
          () -> PngScreenshotEncoder.encode(ActivityPolicyTest.image(0), scale));
    }
    assertEquals(.5, new ActivityProperties().getVisionImageScale());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void actualVisionRequestContainsScaledImagesAndSameMonitorIds() throws Exception {
    var model = mock(ChatModel.class);
    var screen = ActivityCaptureTest.screen(20);
    when(model.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage(ActivityExtractionTest.VALID)))));
    new VisionActivityExtractor(() -> model, () -> OpenAiChatOptions.builder().build(), .5)
        .extract(screen, new ForegroundWindow("Firefox", 1, "X", "window"));
    var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
    verify(model).call(prompt.capture());
    var media = prompt.getValue().getUserMessage().getMedia();
    assertEquals(2, media.size());
    for (int i = 0; i < media.size(); i++) {
      var original = screen.displays().get(i);
      var sent = decode((byte[]) media.get(i).getData());
      assertEquals(Math.max(1, Math.round(original.image().getWidth() * .5)), sent.getWidth());
      assertEquals(Math.max(1, Math.round(original.image().getHeight() * .5)), sent.getHeight());
      assertTrue(prompt.getValue().getUserMessage().getText().contains(original.geometry().id()));
      assertEquals(original.geometry().bounds().width, original.image().getWidth());
    }
  }
}
