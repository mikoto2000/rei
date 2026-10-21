package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 記憶最初画像認識の結果と状態の契約を検証するテスト。 */
class MemoryFirstVisionTest {
  /** 画素数を保持することを確認する。 */
  @Test
  void pngRoundTripPreservesPixelsAndGlobalCachePolicy() throws Exception {
    boolean useCache = ImageIO.getUseCache();
    var source = ActivityPolicyTest.image(137);
    var encoded = PngScreenshotEncoder.encode(source);
    // ImageIO.read(ImageInputStream) closes the provided stream itself.
    var decoded =
        ImageIO.read(
            new javax.imageio.stream.MemoryCacheImageInputStream(
                new java.io.ByteArrayInputStream(encoded)));
    assertEquals(source.getWidth(), decoded.getWidth());
    assertEquals(source.getRGB(10, 10), decoded.getRGB(10, 10));
    assertEquals(useCache, ImageIO.getUseCache());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void unavailablePngWriterFailsWithoutInvokingVision() throws Exception {
    var model = mock(ChatModel.class);
    try (var io = mockStatic(ImageIO.class, CALLS_REAL_METHODS)) {
      io.when(
              () ->
                  ImageIO.write(
                      any(java.awt.image.RenderedImage.class),
                      eq("png"),
                      any(javax.imageio.stream.ImageOutputStream.class)))
          .thenReturn(false);
      var extractor =
          new VisionActivityExtractor(() -> model, () -> OpenAiChatOptions.builder().build());
      assertThrows(
          java.io.IOException.class,
          () ->
              extractor.extract(
                  ActivityCaptureTest.screen(20), new ForegroundWindow("idea", 1, "rei", "1")));
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(model);
    }
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void visionEncodingNeverUsesTheDiskCacheFactory() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage(ActivityExtractionTest.VALID)))));
    var extractor =
        new VisionActivityExtractor(() -> model, () -> OpenAiChatOptions.builder().build());
    try (var io = mockStatic(ImageIO.class, CALLS_REAL_METHODS)) {
      var diskCacheFactoryUsed = new java.util.concurrent.atomic.AtomicBoolean();
      io.when(() -> ImageIO.createImageOutputStream(any()))
          .thenAnswer(
              invocation -> {
                diskCacheFactoryUsed.set(true);
                return invocation.callRealMethod();
              });
      assertEquals(
          2,
          extractor
              .extract(ActivityCaptureTest.screen(20), new ForegroundWindow("idea", 1, "rei", "1"))
              .inference()
              .activities()
              .size());
      // ImageIO's OutputStream convenience overload uses this factory and can silently create
      // imageio*.tmp.
      assertFalse(
          diskCacheFactoryUsed.get(), "ImageIO's disk-cache-capable factory must not be used");
    }
  }
}
