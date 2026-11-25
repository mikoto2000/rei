package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class RunLlmConnectionRestoreGuardTest {
  @TempDir Path root;

  @Test
  void savedConnectionIsRetainedBeforeOptionsAreBound() {
    var restored = execution();
    restored.restoreProgress(progress("first"));
    assertThat(restored.progressSnapshot().connection()).isEqualTo(connection("first"));
    assertThatThrownBy(() -> restored.setModelOptions(options("second")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("connection");
    restored.setModelOptions(options("first"));
  }

  @Test
  void checkpointCannotChangeAnAlreadyBoundConnection() {
    var execution = execution();
    execution.setModelOptions(options("first"));
    assertThatThrownBy(() -> execution.restoreProgress(progress("second")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("connection");
    assertThat(execution.progressSnapshot().connection()).isEqualTo(connection("first"));
  }

  @Test
  void invalidConnectionTypeDoesNotPartiallyAssignOptions() {
    var execution = execution();
    assertThatThrownBy(() -> execution.setModelOptions(OpenAiChatOptions.builder()
        .toolContext(Map.of(ModelOptions.RUN_CONNECTION_CONTEXT_KEY, "invalid")).build()))
        .isInstanceOf(IllegalArgumentException.class);
    execution.setModelOptions(options("first"));
    assertThat(execution.progressSnapshot().connection()).isEqualTo(connection("first"));
  }

  private RunExecutionContext.ProgressState progress(String id) {
    var execution = execution();
    execution.setModelOptions(options(id));
    return execution.progressSnapshot();
  }

  private OpenAiChatOptions options(String id) {
    return OpenAiChatOptions.builder().model("model")
        .toolContext(Map.of(ModelOptions.RUN_CONNECTION_CONTEXT_KEY, connection(id))).build();
  }

  private RunExecutionContext execution() {
    return new RunExecutionContext("run", new OutputLimitRunBudget(1, 10),
        new ProgressEvaluator(root), new AgentEventFactory(Clock.systemUTC()), event -> {});
  }

  private RunLlmConnection connection(String id) {
    var endpoint = new ResolvedLlmEndpoint(id, 1, LlmApiType.CHAT_COMPLETIONS,
        "http://local.invalid/v1", "chat-endpoint:" + id, "model",
        new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()),
        512, null, Duration.ofSeconds(30));
    return new RunLlmConnection(id, true, LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE, null, null, endpoint, Map.of(), Map.of("max_tokens", 512));
  }
}
