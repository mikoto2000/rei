package dev.mikoto2000.rei.activity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityIntegrationTest {
  @Test void truncatedVisionResponseHasActionableDiagnostic() throws Exception {
    var model=mock(ChatModel.class);
    var metadata=org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("length").build();
    when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(""),metadata))));
    var extractor=new VisionActivityExtractor(()->model,()->OpenAiChatOptions.builder().build());
    var error=assertThrows(ActivityOutputParser.InvalidOutput.class,()->extractor.extract(ActivityCaptureTest.screen(20),new ForegroundWindow("idea",1,"rei","1")));
    assertEquals("/:output_limit",error.diagnostic());
  }
  @Test void emptyVisionResponseIsDistinctFromInvalidJson() throws Exception {
    var model=mock(ChatModel.class);
    when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(new ChatResponse(List.of()));
    var extractor=new VisionActivityExtractor(()->model,()->OpenAiChatOptions.builder().build());
    var error=assertThrows(ActivityOutputParser.InvalidOutput.class,()->extractor.extract(ActivityCaptureTest.screen(20),new ForegroundWindow("idea",1,"rei","1")));
    assertEquals("/:empty_response",error.diagnostic());
  }
  @Test void evidenceSettingsDefaultOffAndBindIndependently() {
    var defaults=new ActivityProperties();assertFalse(defaults.isKeepScreenshots());assertFalse(defaults.isKeepOnExtractionFailure());
    var binder=new org.springframework.boot.context.properties.bind.Binder(new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
        Map.of("rei.activity.keep-screenshots","true","rei.activity.keep-on-extraction-failure","true")));
    var configured=binder.bind("rei.activity",ActivityProperties.class).get();
    assertTrue(configured.isKeepScreenshots());assertTrue(configured.isKeepOnExtractionFailure());
  }
  @Test void configurationIsLazyAndDisabled() {
    var provider=mock(dev.mikoto2000.rei.llm.LlmModelProvider.class);
    var ds=mock(javax.sql.DataSource.class);
    new ApplicationContextRunner().withUserConfiguration(ActivityConfiguration.class)
        .withBean(javax.sql.DataSource.class,()->ds)
        .withBean(dev.mikoto2000.rei.llm.LlmModelProvider.class,()->provider)
        .withBean(dev.mikoto2000.rei.core.service.ModelHolderService.class,()->mock(dev.mikoto2000.rei.core.service.ModelHolderService.class))
        .run(context -> {assertNull(context.getStartupFailure());assertFalse(context.getBean(ActivityProperties.class).isEnabled());verifyNoInteractions(ds,provider);});
  }
  @Test void visionRequestHasPerMonitorImagesAndNoTools() throws Exception {
    var model=mock(ChatModel.class);
    when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(ActivityExtractionTest.VALID)))));
    var extractor=new VisionActivityExtractor(()->model,()->OpenAiChatOptions.builder().build());
    var result=extractor.extract(ActivityCaptureTest.screen(20),new ForegroundWindow("firefox",1,"title","1"));
    assertEquals(2,result.inference().activities().size());
    var prompt=org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);verify(model).call(prompt.capture());
    assertEquals(2,prompt.getValue().getUserMessage().getMedia().size());
    var options=(OpenAiChatOptions)prompt.getValue().getOptions();assertFalse(options.getInternalToolExecutionEnabled());assertTrue(options.getToolCallbacks().isEmpty());
    var schema=ActivityOutputParser.schemaForMonitors(List.of("m1","m2"));
    var mapper=new tools.jackson.databind.json.JsonMapper();
    assertEquals(mapper.readTree(schema),mapper.valueToTree(options.getResponseFormat().getJsonSchema().getSchema()));
    assertTrue(prompt.getValue().getSystemMessage().getText().contains(schema));
  }
  @Test void slashCommandsAndDateDispatch() {
    var timeline=mock(ActivityTimeline.class);var capture=mock(ActivityCapture.class);var properties=new ActivityProperties();
    when(timeline.summary(anyString())).thenReturn("timeline");
    var command=new picocli.CommandLine(new ActivityCommand(timeline,capture,properties));
    command.setOut(new java.io.PrintWriter(new java.io.StringWriter()));
    for(var arg:List.of("today","yesterday","summary","2026-09-22")) {assertEquals(0,command.execute(arg));verify(timeline).summary(arg);}
    assertEquals(0,command.execute("pause"));verify(capture).pause();
    assertEquals(0,command.execute("resume"));verify(capture).resume();assertFalse(properties.isEnabled());
  }
  @Test void naturalLanguageToolsAreRegistered() {
    var callbacks=org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(new ActivityTools(mock(ActivityTimeline.class))).build().getToolCallbacks();
    assertEquals(Set.of("activityTimeline","activityBetween"),Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet()));
  }
}
