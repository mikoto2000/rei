package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class ContextLimitExecutionTest {
  @Test
  void hardContextLimitIsAnExplicitUserFacingFailure() {
    var model = mock(ChatModel.class);
    when(model.getOptions())
        .thenReturn(org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build());
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            Flux.error(
                new ExecutionStoppedException(
                    ExecutionStoppedException.Reason.CONTEXT_HARD_LIMIT)));
    var service =
        new ChatExecutionService(
            ChatClient.builder(model).build(),
            new ModelHolderService("test"),
            new CommandCancellationService(),
            Optional.empty());
    var result = service.execute("request");
    assertThat(result.status()).isEqualTo(ChatExecutionResult.Status.FAILED);
    assertThat(result.errorMessage()).contains("CONTEXT_HARD_LIMIT");
  }
}
