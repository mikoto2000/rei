package dev.mikoto2000.rei.activity;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityVisionScaleTest {
  private BufferedImage decode(byte[] bytes) throws Exception {return ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)));}
  @Test void halfScaleReducesBothDimensionsWithoutChangingOriginal() throws Exception {
    var image=new BufferedImage(1920,1080,BufferedImage.TYPE_INT_RGB);image.setRGB(20,20,0xff336699);
    var result=decode(PngScreenshotEncoder.encode(image,.5));
    assertEquals(960,result.getWidth());assertEquals(540,result.getHeight());
    assertEquals(1920,image.getWidth());assertEquals(0xff336699,image.getRGB(20,20));
  }
  @Test void fullScaleRetainsOriginalPixelsAndTinyImagesStayValid() throws Exception {
    var image=ActivityPolicyTest.image(137);var full=decode(PngScreenshotEncoder.encode(image,1));
    assertEquals(image.getWidth(),full.getWidth());assertEquals(image.getRGB(10,10),full.getRGB(10,10));
    var tiny=decode(PngScreenshotEncoder.encode(new BufferedImage(1,3,BufferedImage.TYPE_INT_RGB),.5));
    assertEquals(1,tiny.getWidth());assertEquals(2,tiny.getHeight());
  }
  @Test void invalidScaleIsRejectedBySettingsAndEncoder() {
    for(double scale:new double[]{0,-1,1.1,Double.NaN,Double.POSITIVE_INFINITY}) {
      var p=new ActivityProperties();p.setVisionImageScale(scale);assertThrows(IllegalArgumentException.class,p::validate);
      assertThrows(IllegalArgumentException.class,()->PngScreenshotEncoder.encode(ActivityPolicyTest.image(0),scale));
    }
    assertEquals(.5,new ActivityProperties().getVisionImageScale());
  }
  @Test void actualVisionRequestContainsScaledImagesAndSameMonitorIds() throws Exception {
    var model=mock(ChatModel.class);var screen=ActivityCaptureTest.screen(20);
    when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(ActivityExtractionTest.VALID)))));
    new VisionActivityExtractor(()->model,()->OpenAiChatOptions.builder().build(),.5)
        .extract(screen,new ForegroundWindow("Firefox",1,"X","window"));
    var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(model).call(prompt.capture());
    var media=prompt.getValue().getUserMessage().getMedia();assertEquals(2,media.size());
    for(int i=0;i<media.size();i++) {
      var original=screen.displays().get(i);var sent=decode((byte[])media.get(i).getData());
      assertEquals(Math.max(1,Math.round(original.image().getWidth()*.5)),sent.getWidth());
      assertEquals(Math.max(1,Math.round(original.image().getHeight()*.5)),sent.getHeight());
      assertTrue(prompt.getValue().getUserMessage().getText().contains(original.geometry().id()));
      assertEquals(original.geometry().bounds().width,original.image().getWidth());
    }
  }
}
