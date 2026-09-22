package dev.mikoto2000.rei.activity;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import javax.imageio.ImageIO;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryFirstVisionTest {
  @Test void pngRoundTripPreservesPixelsAndGlobalCachePolicy() throws Exception {
    boolean useCache=ImageIO.getUseCache();var source=ActivityPolicyTest.image(137);
    var encoded=PngScreenshotEncoder.encode(source);
    // ImageIO.read(ImageInputStream) closes the provided stream itself.
    var decoded=ImageIO.read(new javax.imageio.stream.MemoryCacheImageInputStream(new java.io.ByteArrayInputStream(encoded)));
    assertEquals(source.getWidth(),decoded.getWidth());assertEquals(source.getRGB(10,10),decoded.getRGB(10,10));
    assertEquals(useCache,ImageIO.getUseCache());
  }
  @Test void unavailablePngWriterFailsWithoutInvokingVision() throws Exception {
    var model=mock(ChatModel.class);
    try(var io=mockStatic(ImageIO.class,CALLS_REAL_METHODS)) {
      io.when(()->ImageIO.write(any(java.awt.image.RenderedImage.class),eq("png"),any(javax.imageio.stream.ImageOutputStream.class))).thenReturn(false);
      var extractor=new VisionActivityExtractor(()->model,()->OpenAiChatOptions.builder().build());
      assertThrows(java.io.IOException.class,()->extractor.extract(ActivityCaptureTest.screen(20),new ForegroundWindow("idea",1,"rei","1")));
      verifyNoInteractions(model);
    }
  }
  @Test void visionEncodingNeverUsesTheDiskCacheFactory() throws Exception {
    var model=mock(ChatModel.class);
    when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(ActivityExtractionTest.VALID)))));
    var extractor=new VisionActivityExtractor(()->model,()->OpenAiChatOptions.builder().build());
    try(var io=mockStatic(ImageIO.class,CALLS_REAL_METHODS)) {
      var diskCacheFactoryUsed=new java.util.concurrent.atomic.AtomicBoolean();
      io.when(()->ImageIO.createImageOutputStream(any())).thenAnswer(invocation -> {
        diskCacheFactoryUsed.set(true);return invocation.callRealMethod();
      });
      assertEquals(2,extractor.extract(ActivityCaptureTest.screen(20),new ForegroundWindow("idea",1,"rei","1")).inference().activities().size());
      // ImageIO's OutputStream convenience overload uses this factory and can silently create imageio*.tmp.
      assertFalse(diskCacheFactoryUsed.get(), "ImageIO's disk-cache-capable factory must not be used");
    }
  }
}
