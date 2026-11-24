package dev.mikoto2000.rei.computeruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class NativeComputerOptionsTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path diagnostics;
  private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
      captured = new ch.qos.logback.core.read.ListAppender<>();
  private final ch.qos.logback.classic.Logger rootLogger =
      (ch.qos.logback.classic.Logger)
          org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

  @org.junit.jupiter.api.BeforeEach
  void captureLogs() {
    captured.start();
    rootLogger.addAppender(captured);
  }

  @org.junit.jupiter.api.AfterEach
  void checkDiagnostics() throws Exception {
    rootLogger.detachAppender(captured);
    captured.stop();
    assertThat(captured.list)
        .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE"));
    try (var files = java.nio.file.Files.walk(diagnostics)) {
      for (var file :
          files
              .filter(java.nio.file.Files::isRegularFile)
              .filter(file -> !file.toString().endsWith(".png"))
              .toList()) assertThat(java.nio.file.Files.readString(file)).doesNotContain("PRIVATE");
    }
  }

  private ComputerObservation observation() {
    var original = SpringAiComputerVisionModelTest.observation();
    return new ComputerObservation(
        original.goal(),
        original.screenshot(),
        original.recentHistory(),
        original.step(),
        original.maxSteps(),
        diagnostics);
  }

  private final OpenAiResponsesChatOptions base =
      OpenAiResponsesChatOptions.builder().model("native-vision").maxOutputTokens(73).build();

  private void check(Prompt prompt) {
    var sent = (OpenAiResponsesChatOptions) prompt.getOptions();
    assertThat(sent.getMaxOutputTokens()).isEqualTo(73);
    assertThat(sent.getToolCallbacks()).isEmpty();
    assertThat(sent.getToolChoice()).isNull();
    assertThat(sent.getToolContext().get("rei.llm.replay-owner")).asString().startsWith("feature:");
    assertThat(prompt.getUserMessage().getMedia()).isNotEmpty();
  }

  static ChatResponse response(String text) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .parts(
                        List.of(
                            new ReasoningPart(
                                null,
                                "PRIVATE reasoning",
                                new OpaquePayload("openai", "encrypted_content", "PRIVATE opaque"),
                                Map.of()),
                            new TextPart(text, null, Map.of())))
                    .build())),
        org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
            .keyValue("private-data", "PRIVATE metadata")
            .build());
  }

  @Test
  void plannerUsesNativeSchemaWithoutDisclosingHiddenParts() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenAnswer(inv -> {
          check(inv.getArgument(0));
          return response(ActionValidationTest.json("DONE", "reason", "\"visible\""));
        });
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(SpringAiComputerVisionModel.class);
    var logs =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logs.start();
    logger.addAppender(logs);
    try {
      var vision =
          SpringAiComputerVisionModel.class
              .getConstructor(ChatModel.class, Supplier.class, BooleanSupplier.class, int.class)
              .newInstance(
                  model,
                  (Supplier<OpenAiResponsesChatOptions.Builder>) base::mutate,
                  (BooleanSupplier) () -> false,
                  0);
      assertThat(vision.decide(observation())).isInstanceOf(ComputerAction.Done.class);
      assertThat(logs.list)
          .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE"));
    } finally {
      logger.detachAppender(logs);
      logs.stop();
    }
  }

  @Test
  void verifierUsesNativeIsolatedOptionsAndSafeDiagnosticText() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenAnswer(inv -> {
          check(inv.getArgument(0));
          return response("{\"approved\":true,\"reason\":\"visible\"}");
        });
    var constructor =
        PlannerGroundingVerifier.class.getDeclaredConstructor(
            ChatModel.class,
            Supplier.class,
            BooleanSupplier.class,
            java.util.function.Function.class);
    var verifier =
        constructor.newInstance(
            model,
            (Supplier<OpenAiResponsesChatOptions.Builder>) base::mutate,
            (BooleanSupplier) () -> false,
            (java.util.function.Function<java.awt.Point, String>) point -> "{}");
    var observation = observation();
    verifier.verify(
        observation, observation.screenshot().displays().getFirst().image(), "fixture", null);
    verify(model).call(any(Prompt.class));
  }

  @Test
  void showUiGroundingUsesNativeOutputBudget() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenAnswer(inv -> {
          check(inv.getArgument(0));
          return response("[0.5,0.5]");
        });
    ComputerVisionModel planner =
        o ->
            new ComputerAction.Click(
                new ComputerAction.Target(10, 10, "fixture"), .9, ComputerAction.Risk.LOW);
    var constructor =
        ShowUiComputerVisionModel.class.getDeclaredConstructor(
            ComputerVisionModel.class,
            ChatModel.class,
            Supplier.class,
            BooleanSupplier.class,
            GroundingProtocol.class,
            GroundingVerifier.class);
    var vision =
        constructor.newInstance(
            planner,
            model,
            (Supplier<OpenAiResponsesChatOptions.Builder>) base::mutate,
            (BooleanSupplier) () -> false,
            GroundingProtocol.SHOWUI,
            (GroundingVerifier) (o, i, t, point) -> {});
    assertThat(vision.decide(observation())).isInstanceOf(ComputerAction.Click.class);
    verify(model, times(2)).call(any(Prompt.class));
  }

 @Test void uiTarsCannotSilentlyDropItsExplicitFrequencyPenaltyOnResponses() throws Exception {
  var model=mock(ChatModel.class);
  ComputerVisionModel planner=o->new ComputerAction.Click(new ComputerAction.Target(10,10,"fixture"),.9,ComputerAction.Risk.LOW);
  var constructor=ShowUiComputerVisionModel.class.getDeclaredConstructor(ComputerVisionModel.class,ChatModel.class,Supplier.class,BooleanSupplier.class,GroundingProtocol.class,GroundingVerifier.class);
  var vision=constructor.newInstance(planner,model,(Supplier<OpenAiResponsesChatOptions.Builder>)base::mutate,(BooleanSupplier)()->false,GroundingProtocol.UITARS,(GroundingVerifier)(o,i,t,point)->{});
  org.assertj.core.api.Assertions.assertThatThrownBy(()->vision.decide(observation()))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Frequency penalty");
  verify(model,never()).call(any(Prompt.class));
  verify(model,never()).stream(any(Prompt.class));
 }
}
