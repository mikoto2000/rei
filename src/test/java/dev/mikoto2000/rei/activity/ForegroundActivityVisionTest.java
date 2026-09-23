package dev.mikoto2000.rei.activity;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForegroundActivityVisionTest {
  @Test void logsReportedReasoningTokensWithoutReasoningText() throws Exception {
    var usage=new org.springframework.ai.openai.api.OpenAiApi.Usage(1800,100,1900,null,
        new org.springframework.ai.openai.api.OpenAiApi.Usage.CompletionTokenDetails(1500,null,null,null));
    var metadata=org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().usage(new org.springframework.ai.chat.metadata.DefaultUsage(100,1800,1900,usage)).build();
    var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(response(VALID,"stop").getResults(),metadata));
    var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(VisionActivityExtractor.class);
    var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
    try(var scope=org.slf4j.MDC.putCloseable("activityScope","foreground")) {
      new VisionActivityExtractor(()->model,()->OpenAiChatOptions.builder().build()).extract(ActivityCaptureTest.screen(20),new ForegroundWindow("Firefox",1,"PRIVATE_TITLE","1"));
      assertTrue(appender.list.stream().anyMatch(e->e.getFormattedMessage().contains("reasoning_tokens=1500") && e.getFormattedMessage().contains("max_output_tokens=2048")));
      assertTrue(appender.list.stream().noneMatch(e->e.getFormattedMessage().contains("PRIVATE_TITLE") || e.getFormattedMessage().contains(VALID)));
    }finally{logger.detachAppender(appender);appender.stop();}
  }
  static final String VALID="""
      {"category":"research","application":"Firefox","service":"ChatGPT","projectCandidate":null,"contentCandidate":null,"summary":"調査に関する画面","confidence":0.9}
      """;
  @Test void foregroundUsesOneSmallClassificationWithoutModelGeneratedObservationsOrMonitorIds() throws Exception {
    var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(response(VALID,"stop"));
    try(var scope=org.slf4j.MDC.putCloseable("activityScope","foreground")) {
      var result=new VisionActivityExtractor(()->model,()->OpenAiChatOptions.builder().maxTokens(32768).build()).extract(ActivityCaptureTest.screen(20),new ForegroundWindow("Firefox",1,"ChatGPT","1"));
      assertEquals(1,result.inference().activities().size());assertEquals("research",result.inference().activities().getFirst().type());
      assertEquals("",result.inference().activities().getFirst().projectCandidate());
    }
    var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(model).call(prompt.capture());
    var options=(OpenAiChatOptions)prompt.getValue().getOptions();assertEquals(2048,options.getMaxCompletionTokens());assertNull(options.getMaxTokens());
    var schema=new tools.jackson.databind.json.JsonMapper().valueToTree(options.getResponseFormat().getJsonSchema().getSchema());
    assertEquals(7,schema.get("properties").size());assertFalse(schema.get("properties").has("observations"));assertFalse(schema.get("properties").has("activities"));assertFalse(schema.get("properties").has("monitor"));
    assertTrue(prompt.getValue().getSystemMessage().getText().length()<1200);assertTrue(options.getToolCallbacks().isEmpty());
  }
  @Test void tokenFixtureFailsAt1024AndSucceedsAt2048WithoutRepeatingTruncatedRequest() throws Exception {
    var model=mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenAnswer(i->response(((OpenAiChatOptions)((Prompt)i.getArgument(0)).getOptions()).getMaxCompletionTokens()<2048?"":VALID,
        ((OpenAiChatOptions)((Prompt)i.getArgument(0)).getOptions()).getMaxCompletionTokens()<2048?"length":"stop"));
    try(var scope=org.slf4j.MDC.putCloseable("activityScope","foreground")) {
      var low=new VisionActivityExtractor(()->model,()->OpenAiChatOptions.builder().build(),.5,1024);
      var error=assertThrows(ActivityOutputParser.InvalidOutput.class,()->low.extract(ActivityCaptureTest.screen(20),new ForegroundWindow("Firefox",1,"ChatGPT","1")));
      assertEquals("/:output_limit",error.diagnostic());verify(model).call(any(Prompt.class));
      var normal=new VisionActivityExtractor(()->model,()->OpenAiChatOptions.builder().build());
      assertEquals("research",normal.extract(ActivityCaptureTest.screen(20),new ForegroundWindow("Firefox",1,"ChatGPT","1")).inference().activities().getFirst().type());
      verify(model,times(2)).call(any(Prompt.class));
    }
  }
  @Test void lightweightValidationRejectsExtraFieldsTrailingJsonAndInvalidCategories() {
    var parser=new ForegroundActivityParser();
    for(String json:List.of(VALID+" {}",VALID.replace("research","not-a-category"),VALID.replace("\"confidence\":0.9","\"confidence\":1.1"),VALID.replace("\"confidence\":0.9","\"confidence\":0.9,\"observations\":[]")))
      assertThrows(ActivityOutputParser.InvalidOutput.class,()->parser.parse(json,"m1"));
  }
  private static ChatResponse response(String content,String finish) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(content),org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason(finish).build())));
  }
}
