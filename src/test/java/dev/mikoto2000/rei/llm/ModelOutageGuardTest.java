package dev.mikoto2000.rei.llm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class ModelOutageGuardTest {
  @Test
  void outageBlocksNewRequestsAndAllowsOnlyOneRecoveryProbe() {
    var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
    var clock = mock(Clock.class);
    when(clock.instant()).thenAnswer(call -> now.get());
    var guard = new ModelOutageGuard(clock, Duration.ofSeconds(30));
    var old = guard.acquire();
    var request = guard.acquire();
    guard.failed(request, new java.net.ConnectException("PRIVATE_HOST"));
    guard.completed(old); // A late old response cannot erase the newer outage.
    var rejected = assertThrows(ModelOutageGuard.CoolingDown.class, guard::acquire);
    assertEquals(
        "model_cooling_down",
        dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(rejected).code());
    assertFalse(rejected.getMessage().contains("PRIVATE"));
    now.set(Instant.EPOCH.plusSeconds(30));
    var probe = guard.acquire();
    assertThrows(ModelOutageGuard.CoolingDown.class, guard::acquire);
    guard.cancelled(probe);
    guard.completed(guard.acquire());
    assertDoesNotThrow(guard::acquire);
  }

  @Test
  void validationAndCancellationDoNotTripTheGuard() {
    var guard = new ModelOutageGuard(Clock.systemUTC(), Duration.ofSeconds(30));
    guard.failed(guard.acquire(), new IllegalArgumentException("bad tool arguments"));
    guard.failed(guard.acquire(), new java.util.concurrent.CancellationException());
    assertDoesNotThrow(guard::acquire);
  }

  @Test
  void partialStreamFailureNeverStartsFallbackOrRepeatsToolCalls() {
    var primary = mock(ChatModel.class);
    var fallback = mock(ChatModel.class);
    var prompt = new Prompt("request");
    var partial =
        new ChatResponse(
            List.of(
                new Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage("partial"))));
    when(primary.stream(prompt))
        .thenReturn(Flux.concat(Flux.just(partial), Flux.error(new java.net.ConnectException())));
    var received = new java.util.ArrayList<ChatResponse>();
    assertThrows(
        RuntimeException.class,
        () ->
            new FallbackChatModel("chat", primary, fallback, "primary")
                .stream(prompt).doOnNext(received::add).blockLast());
    assertEquals(List.of(partial), received);
    verifyNoInteractions(fallback);
  }
}
