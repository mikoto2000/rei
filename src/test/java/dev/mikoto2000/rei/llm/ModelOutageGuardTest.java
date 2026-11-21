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
  void socketAndProviderTimeoutsAlsoStartCooldown() {
    for (var failure :
        List.of(
            new java.net.SocketTimeoutException("PRIVATE"),
            new java.util.concurrent.TimeoutException(),
            org.springframework.web.reactive.function.client.WebClientResponseException.create(
                408,
                "timeout",
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0],
                java.nio.charset.StandardCharsets.UTF_8))) {
      var guard = new ModelOutageGuard(Clock.systemUTC(), Duration.ofSeconds(30));
      guard.failed(guard.acquire(), failure);
      assertThrows(ModelOutageGuard.CoolingDown.class, guard::acquire);
    }
  }

  @Test
  void modelBoundaryReportsCooldownWithoutInvokingTheProviderAgain() {
    var delegate = mock(ChatModel.class);
    var prompt = new Prompt("request");
    when(delegate.stream(prompt))
        .thenReturn(Flux.error(new java.net.ConnectException("PRIVATE_HOST")));
    var events = new java.util.ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    var model =
        new AgentEventChatModel(
            "chat",
            delegate,
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            events::add,
            null,
            run -> false,
            new ModelOutageGuard(Clock.systemUTC(), Duration.ofSeconds(30)));
    assertThrows(RuntimeException.class, () -> model.stream(prompt).blockLast());
    assertThrows(ModelOutageGuard.CoolingDown.class, () -> model.stream(prompt).blockLast());
    org.mockito.Mockito.verify(delegate, org.mockito.Mockito.times(1)).stream(prompt);
    assertEquals(
        2,
        events.stream()
            .filter(e -> e.type() == dev.mikoto2000.rei.event.AgentEventType.LLM_REQUEST_FAILED)
            .count());
    assertEquals(
        1,
        events.stream()
            .filter(e -> e.type() == dev.mikoto2000.rei.event.AgentEventType.LLM_REQUEST_STARTED)
            .count());
  }

  @Test
  void synchronousCooldownDoesNotReportRequestSent() {
    var delegate = mock(ChatModel.class);
    var guard = new ModelOutageGuard(Clock.systemUTC(), Duration.ofSeconds(30));
    guard.failed(guard.acquire(), new java.net.ConnectException());
    var events = new java.util.ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    var model =
        new AgentEventChatModel(
            "chat",
            delegate,
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            events::add,
            null,
            run -> false,
            guard);
    assertThrows(ModelOutageGuard.CoolingDown.class, () -> model.call(new Prompt("work")));
    verifyNoInteractions(delegate);
    assertEquals(
        0,
        events.stream()
            .filter(e -> e.type() == dev.mikoto2000.rei.event.AgentEventType.LLM_REQUEST_STARTED)
            .count());
  }

  @Test
  void retryWaitsForCooldownAndCancellingItsProbeReleasesAdmission() throws Exception {
    var clock = mock(Clock.class);
    var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
    when(clock.instant()).thenAnswer(call -> now.get());
    var guard = new ModelOutageGuard(clock, Duration.ofMillis(30));
    guard.failed(guard.acquire(), new java.net.ConnectException());
    var delegate = mock(ChatModel.class);
    var response =
        new ChatResponse(
            List.of(
                new Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage("recovered"))));
    when(delegate.stream(org.mockito.ArgumentMatchers.any(Prompt.class)))
        .thenReturn(Flux.just(response));
    var events =
        new java.util.concurrent.CopyOnWriteArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    var model =
        new AgentEventChatModel(
            "chat",
            delegate,
            new dev.mikoto2000.rei.event.AgentEventFactory(clock),
            events::add,
            null,
            run -> false,
            guard);
    var result =
        model.stream(new Prompt("work"))
            .contextWrite(values -> values.put("rei.modelAdmission.waitForRecovery", true))
            .collectList()
            .toFuture();
    verifyNoInteractions(delegate);
    assertEquals(0, events.size());
    now.set(Instant.EPOCH.plusMillis(30));
    assertEquals(List.of(response), result.get(3, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(
        1,
        events.stream()
            .filter(e -> e.type() == dev.mikoto2000.rei.event.AgentEventType.LLM_REQUEST_STARTED)
            .count());

    guard.failed(guard.acquire(), new java.net.ConnectException());
    org.mockito.Mockito.clearInvocations(delegate);
    var waiting =
        model.stream(new Prompt("cancel while waiting"))
            .contextWrite(values -> values.put("rei.modelAdmission.waitForRecovery", true))
            .subscribe();
    waiting.dispose();
    verifyNoInteractions(delegate);
    assertThrows(ModelOutageGuard.CoolingDown.class, guard::acquire);

    now.set(Instant.EPOCH.plusMillis(60));
    when(delegate.stream(org.mockito.ArgumentMatchers.any(Prompt.class))).thenReturn(Flux.never());
    var probe = model.stream(new Prompt("cancel active probe")).subscribe();
    assertThrows(ModelOutageGuard.CoolingDown.class, guard::acquire);
    probe.dispose();
    assertDoesNotThrow(guard::acquire);
  }

  @Test
  void occupiedProbeWaitIsCancelledAtTheCallersDeadline() {
    var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
    var clock = mock(Clock.class);
    when(clock.instant()).thenAnswer(call -> now.get());
    var guard = new ModelOutageGuard(clock, Duration.ofMillis(1));
    guard.failed(guard.acquire(), new java.net.ConnectException());
    now.set(Instant.EPOCH.plusMillis(1));
    var probe = guard.acquire();
    var timeout =
        assertThrows(
            IllegalStateException.class,
            () -> guard.acquireAfterCooldown(() -> {}).block(Duration.ofMillis(250)));
    assertEquals(java.util.concurrent.TimeoutException.class, timeout.getCause().getClass());
    // A cancelled waiter does not release a different request's recovery probe.
    assertThrows(ModelOutageGuard.CoolingDown.class, guard::acquire);
    guard.cancelled(probe);
    assertDoesNotThrow(guard::acquire);
  }

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
