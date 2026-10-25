package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

@Tag("integration")
class SecretaryPrivateDraftTest {
  @Test
  void privateDraftReservesOnceAndNeverUsesConversationClientOrMemory() {
    var provider = mock(LlmModelProvider.class);
    var model = mock(ChatModel.class);
    when(provider.privateSecretaryModel("exact")).thenReturn(model);
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("Draft only"))))));
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var budget =
        OutputLimitRunBudget.LlmCallReservation.of(() -> calls.incrementAndGet() == 1, () -> 1);
    var service =
        new ChatExecutionService(
            null,
            null,
            provider,
            new LlmProperties(),
            new CommandCancellationService(),
            Optional.empty(),
            Optional.empty());
    var owner = new AgentRunContext("private", "s", Path.of("."), "p").asReadOnly();
    var result = service.executePrivateSecretaryDraft(owner, "[untrusted] reply", budget, "exact");
    assertTrue(result.success());
    assertEquals("Draft only", result.text());
    assertEquals(1, calls.get());
    verify(provider).privateSecretaryModel("exact");
    verifyNoMoreInteractions(provider);
    verify(model).stream(any(Prompt.class));
  }

  @Test
  void zeroBudgetAndQueuedCancellationPreventTheModelRequest() {
    var provider = mock(LlmModelProvider.class);
    var model = mock(ChatModel.class);
    when(provider.privateSecretaryModel("exact")).thenReturn(model);
    var cancellation = new CommandCancellationService();
    var service =
        new ChatExecutionService(
            null,
            null,
            provider,
            new LlmProperties(),
            cancellation,
            Optional.empty(),
            Optional.empty());
    var owner = new AgentRunContext("private", "s", Path.of("."), "p").asReadOnly();
    assertFalse(
        service
            .executePrivateSecretaryDraft(
                owner,
                "private",
                OutputLimitRunBudget.LlmCallReservation.of(() -> false, () -> 0),
                "exact")
            .success());
    verifyNoInteractions(model);
    cancellation.cancelRun("private");
    assertEquals(
        ChatExecutionResult.Status.CANCELLED,
        service
            .executePrivateSecretaryDraft(
                owner,
                "private",
                OutputLimitRunBudget.LlmCallReservation.of(
                    () -> {
                      throw new AssertionError("Must not reserve");
                    },
                    () -> 1),
                "exact")
            .status());
    Thread.interrupted();
    verifyNoInteractions(model);
  }
}
