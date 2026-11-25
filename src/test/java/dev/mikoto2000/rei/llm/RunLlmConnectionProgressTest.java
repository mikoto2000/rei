package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.modeloptions.ModelOptions;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.openai.OpenAiChatOptions;

class RunLlmConnectionProgressTest {
  @TempDir Path root;

  @Test
  void progressSnapshotCarriesTheAcceptedConnectionRatherThanCurrentSelection() {
    var connection = connection();
    var execution = execution();
    execution.setModelOptions(OpenAiChatOptions.builder().model("model")
        .toolContext(Map.of(ModelOptions.RUN_CONNECTION_CONTEXT_KEY, connection)).build());
    assertThat(execution.progressSnapshot().connection()).isEqualTo(connection);
  }

  @Test
  void durableProgressAndExplicitResumePreserveTheSameConnection() throws Exception {
    var connection = connection();
    var execution = execution();
    execution.setModelOptions(OpenAiChatOptions.builder().model("model")
        .toolContext(Map.of(ModelOptions.RUN_CONNECTION_CONTEXT_KEY, connection)).build());
    var mapper = new ObjectMapper().findAndRegisterModules();
    var restored = mapper.readValue(mapper.writeValueAsBytes(execution.progressSnapshot()),
        RunExecutionContext.ProgressState.class);
    assertThat(restored.connection()).isEqualTo(connection);
    assertThat(restored.forExplicitResume().connection()).isEqualTo(connection);
  }

  @Test
  void legacyProgressWithoutAConnectionRetainsItsOldRepresentation() throws Exception {
    var execution = execution();
    execution.setModelOptions(OpenAiChatOptions.builder().model("legacy").build());
    var progress = execution.progressSnapshot();
    assertThat(progress.connection()).isNull();
    assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(progress))
        .doesNotContain("run-connection", "\"connection\"");
  }

  private RunExecutionContext execution() {
    return new RunExecutionContext("run", new OutputLimitRunBudget(1, 10),
        new ProgressEvaluator(root), new AgentEventFactory(Clock.systemUTC()), event -> {});
  }

  private RunLlmConnection connection() {
    var endpoint = new ResolvedLlmEndpoint("local", 1, LlmApiType.CHAT_COMPLETIONS,
        "http://local.invalid/v1", "chat-endpoint:local", "model",
        new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()),
        512, null, Duration.ofSeconds(30));
    return new RunLlmConnection("local", true, LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE, null, null, endpoint,
        Map.of(LlmEndpointCapabilities.Capability.CHAT_COMPLETIONS, LlmEndpointCapabilities.Support.SUPPORTED),
        Map.of("max_tokens", 512));
  }
}
