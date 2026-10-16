package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@org.junit.jupiter.api.Tag("integration")
class ActivityIntegrationTest {
  @Test
  void truncatedVisionResponseHasActionableDiagnostic() throws Exception {
    var model = mock(ChatModel.class);
    var metadata =
        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
            .finishReason("length")
            .build();
    when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(""), metadata))));
    var extractor =
        new VisionActivityExtractor(() -> model, () -> OpenAiChatOptions.builder().build());
    var error =
        assertThrows(
            ActivityOutputParser.InvalidOutput.class,
            () ->
                extractor.extract(
                    ActivityCaptureTest.screen(20), new ForegroundWindow("idea", 1, "rei", "1")));
    assertEquals("/:output_limit", error.diagnostic());
  }

  @Test
  void emptyVisionResponseIsDistinctFromInvalidJson() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(new ChatResponse(List.of()));
    var extractor =
        new VisionActivityExtractor(() -> model, () -> OpenAiChatOptions.builder().build());
    var error =
        assertThrows(
            ActivityOutputParser.InvalidOutput.class,
            () ->
                extractor.extract(
                    ActivityCaptureTest.screen(20), new ForegroundWindow("idea", 1, "rei", "1")));
    assertEquals("/:empty_response", error.diagnostic());
  }

  @Test
  void evidenceSettingsDefaultOffAndBindIndependently() {
    var defaults = new ActivityProperties();
    assertFalse(defaults.isKeepScreenshots());
    assertFalse(defaults.isKeepOnExtractionFailure());
    var binder =
        new org.springframework.boot.context.properties.bind.Binder(
            new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                Map.of(
                    "rei.activity.keep-screenshots",
                    "true",
                    "rei.activity.keep-on-extraction-failure",
                    "true")));
    var configured = binder.bind("rei.activity", ActivityProperties.class).get();
    assertTrue(configured.isKeepScreenshots());
    assertTrue(configured.isKeepOnExtractionFailure());
  }

  @Test
  void configurationIsLazyAndDisabled() {
    var provider = mock(dev.mikoto2000.rei.llm.LlmModelProvider.class);
    var ds = mock(javax.sql.DataSource.class);
    new ApplicationContextRunner()
        .withUserConfiguration(ActivityConfiguration.class)
        .withBean(javax.sql.DataSource.class, () -> ds)
        .withBean(dev.mikoto2000.rei.llm.LlmModelProvider.class, () -> provider)
        .withBean(
            dev.mikoto2000.rei.core.service.ModelHolderService.class,
            () -> mock(dev.mikoto2000.rei.core.service.ModelHolderService.class))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertFalse(context.getBean(ActivityProperties.class).isEnabled());
              verifyNoInteractions(ds, provider);
            });
  }

  @Test
  void visionRequestHasPerMonitorImagesAndNoTools() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage(ActivityExtractionTest.VALID)))));
    var extractor =
        new VisionActivityExtractor(() -> model, () -> OpenAiChatOptions.builder().build());
    var result =
        extractor.extract(
            ActivityCaptureTest.screen(20), new ForegroundWindow("firefox", 1, "title", "1"));
    assertEquals(2, result.inference().activities().size());
    var prompt =
        org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
    verify(model).call(prompt.capture());
    assertEquals(2, prompt.getValue().getUserMessage().getMedia().size());
    var options = (OpenAiChatOptions) prompt.getValue().getOptions();
    assertNull(options.getToolChoice());
    assertTrue(options.getToolCallbacks().isEmpty());
    var schema = ActivityOutputParser.schemaForMonitors(List.of("m1", "m2"));
    var mapper = new tools.jackson.databind.json.JsonMapper();
    assertEquals(
        mapper.readTree(schema), mapper.readTree(options.getResponseFormat().getJsonSchema()));
    assertTrue(prompt.getValue().getSystemMessage().getText().contains(schema));
  }

  @Test
  void slashCommandsAndDateDispatch() {
    var timeline = mock(ActivityTimeline.class);
    var capture = mock(ActivityCapture.class);
    var properties = new ActivityProperties();
    var presentation = mock(ActivityTimelinePresentationService.class);
    when(presentation.format(anyString(), eq(false))).thenReturn("timeline");
    var activityCommand = new ActivityCommand(timeline, capture, properties);
    activityCommand.timelinePresentation(presentation);
    var command = new picocli.CommandLine(activityCommand);
    command.setOut(new java.io.PrintWriter(new java.io.StringWriter()));
    for (var arg : List.of("today", "yesterday", "2026-09-22")) {
      assertEquals(0, command.execute(arg));
      verify(presentation).format(arg, false);
    }
    assertEquals(0, command.execute("summary"));
    verify(timeline).trendSummary("today");
    assertEquals(0, command.execute("pause"));
    verify(capture).pause();
    assertEquals(0, command.execute("resume"));
    verify(capture).resume();
    assertFalse(properties.isEnabled());
  }

  @Test
  void naturalLanguageToolsAreRegistered() {
    var callbacks =
        org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
            .toolObjects(new ActivityTools(mock(ActivityTimeline.class)))
            .build()
            .getToolCallbacks();
    assertEquals(
        Set.of("activityTimeline", "activityBetween", "activitySummary", "activityWorkInferences"),
        Arrays.stream(callbacks)
            .map(c -> c.getToolDefinition().name())
            .collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  void summaryToolUsesCompressedProjection() {
    var timeline = mock(ActivityTimeline.class);
    when(timeline.summary("today")).thenReturn("compressed summary");
    assertEquals("compressed summary", new ActivityTools(timeline).activitySummary("today"));
    verify(timeline).summary("today");
    verify(timeline, never()).query(anyString());
  }
}
