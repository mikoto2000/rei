package dev.mikoto2000.rei.activity;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityTimingTest {
  @Test void successfulVisionLogsStageDurationsAndSizesWithoutPrivateContent() throws Exception {check(false);}
  @Test void outputLimitAlsoLogsTimingsAndReasonWithoutProviderOutput() throws Exception {check(true);}
  private void check(boolean limit) throws Exception {
    var model=mock(ChatModel.class);
    var metadata=org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason(limit?"length":"stop").build();
    when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(limit?"PRIVATE_RESPONSE":ActivityExtractionTest.VALID),metadata))));
    var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(VisionActivityExtractor.class);
    var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
    try {
      var extractor=new VisionActivityExtractor(()->model,()->OpenAiChatOptions.builder().build());
      var fg=new ForegroundWindow("firefox",1,"PRIVATE_TITLE","1");
      if(limit) assertThrows(ActivityOutputParser.InvalidOutput.class,()->extractor.extract(ActivityCaptureTest.screen(20),fg));
      else extractor.extract(ActivityCaptureTest.screen(20),fg);
      var events=appender.list.stream().map(e->e.getFormattedMessage()).toList();
      assertTrue(events.stream().anyMatch(s->s.contains("Activity vision timing") && s.contains("input_prepare_ms=") && s.contains("llm_roundtrip_ms=") && s.contains("output_parse_ms=") && s.contains("png_bytes=") && s.contains("status="+(limit?"output_limit":"success"))));
      assertTrue(events.stream().noneMatch(s->s.contains("PRIVATE_TITLE")||s.contains("PRIVATE_RESPONSE")||s.contains(ActivityExtractionTest.VALID)));
    } finally {logger.detachAppender(appender);appender.stop();}
  }
  @Test void networkFailureHasRoundtripDurationAndNoPayload() throws Exception {
    var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("PRIVATE_NETWORK_DATA"));
    var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(VisionActivityExtractor.class);
    var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
    try {
      assertThrows(IllegalStateException.class,()->new VisionActivityExtractor(()->model,()->OpenAiChatOptions.builder().build()).extract(ActivityCaptureTest.screen(20),new ForegroundWindow("app",1,"","1")));
      assertTrue(appender.list.stream().anyMatch(e->e.getFormattedMessage().contains("status=request_failed")));
      assertTrue(appender.list.stream().noneMatch(e->e.getFormattedMessage().contains("PRIVATE_NETWORK_DATA")||e.getThrowableProxy()!=null));
    } finally {logger.detachAppender(appender);appender.stop();}
  }
}
