package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.modeloptions.ChatPlanAccounts;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Request lifecycle fixtures: no account, HTTP, or model inference. */
class PlanRequestChatModelTest {
  private final ChatModel delegate = mock(ChatModel.class);
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicInteger admitted = new AtomicInteger();
  private final AtomicInteger released = new AtomicInteger();
  private final CompletableFuture<ChatPlanAccounts.Invalidation> invalidation =
      new CompletableFuture<>();
  private final Prompt prompt = new Prompt("fixture");
  private final ChatResponse response =
      new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));

  private ChatPlanAccounts.Request begin() {
    assertThat(active.incrementAndGet()).as("shared parent/child capacity").isEqualTo(1);
    admitted.incrementAndGet();
    return new ChatPlanAccounts.Request() {
      private final AtomicBoolean closed = new AtomicBoolean();

      public java.util.concurrent.CompletionStage<ChatPlanAccounts.Invalidation> invalidated() {
        return invalidation.minimalCompletionStage();
      }

      public void close() {
        if (closed.compareAndSet(false, true)) {
          active.decrementAndGet();
          released.incrementAndGet();
        }
      }
    };
  }

  private PlanRequestChatModel model() {
    return new PlanRequestChatModel(delegate, this::begin);
  }

  @Test
  void admissionsAreColdAndReleasedBeforeDownstreamChildRequests() {
    when(delegate.stream(any(Prompt.class)))
        .thenAnswer(ignored -> {
          assertThat(active).hasValue(1);
          return Flux.just(response);
        });
    var model = model();
    var parent = model.stream(prompt);
    assertThat(admitted).hasValue(0);
    assertThat(
            parent
                .concatWith(Flux.defer(() -> model.stream(prompt)))
                .collectList()
                .block(Duration.ofSeconds(2)))
        .containsExactly(response, response);
    assertThat(active).hasValue(0);
    assertThat(admitted).hasValue(2);
    assertThat(released).hasValue(2);
    assertThat(invalidation).isNotDone();
  }

  @Test
  void failedRequestsReleaseBeforeDownstreamRecoveryAndCannotRetryUnknownOutcome() {
    var failure = new ResponsesTransportException(ResponsesTransportException.Category.TIMEOUT);
    when(delegate.stream(any(Prompt.class))).thenReturn(Flux.error(failure));
    var model = model();
    var observed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var recoveryActive = new AtomicInteger(-1);
    assertThat(
            model.stream(prompt)
                .onErrorResume(error -> {
                  recoveryActive.set(active.get());
                  observed.set(error);
                  when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response));
                  return model.stream(prompt);
                })
                .collectList()
                .block(Duration.ofSeconds(2)))
        .containsExactly(response);
    assertThat(recoveryActive).hasValue(0);
    assertThat(observed.get()).isInstanceOf(ResponsesTransportException.class);
    assertThat(((ResponsesTransportException) observed.get()).retryAllowed()).isFalse();
    assertThat(observed.get().getCause()).isNull();
    assertThat(admitted).hasValue(2);
    assertThat(released).hasValue(2);
  }

  @Test
  void subscriberCancellationCancelsUpstreamAndReleasesExactlyOnce() {
    var cancelled = new AtomicBoolean();
    when(delegate.stream(any(Prompt.class)))
        .thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
    var subscription = model().stream(prompt).subscribe();
    assertThat(active).hasValue(1);
    subscription.dispose();
    subscription.dispose();
    assertThat(cancelled).isTrue();
    assertThat(active).hasValue(0);
    assertThat(released).hasValue(1);
    assertThat(invalidation).isNotDone();
  }

  @ParameterizedTest
  @EnumSource(ChatPlanAccounts.Invalidation.class)
  void accountInvalidationCancelsUpstreamAndPreservesSafeReason(
      ChatPlanAccounts.Invalidation reason) {
    var cancelled = new AtomicBoolean();
    when(delegate.stream(any(Prompt.class)))
        .thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
    var observed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var subscription = model().stream(prompt).subscribe(ignored -> {}, observed::set);
    try {
      invalidation.complete(reason);
      var error = observed.get();
      assertThat(error).isInstanceOf(dev.mikoto2000.rei.toolcontract.ExternalReadFailure.class);
      var safe = (dev.mikoto2000.rei.toolcontract.ExternalReadFailure) error;
      assertThat(safe.retryAllowed()).isFalse();
      assertThat(safe.diagnosticCode())
          .isEqualTo(
              switch (reason) {
                case STOPPED -> "chatgpt_cancelled";
                case SIGNED_OUT, REGISTRATION_CHANGED -> "chatgpt_auth_required";
                case AUTHENTICATION_INVALID -> "chatgpt_auth_invalid";
                case CONSENT_REQUIRED -> "chatgpt_consent_required";
              });
      assertThat(error.getCause()).isNull();
    } finally {
      subscription.dispose();
    }
    assertThat(cancelled).isTrue();
    assertThat(active).hasValue(0);
    assertThat(released).hasValue(1);
  }

  @Test
  void blockingCallsUseTheSameStreamingAdmissionAndReleaseBeforeReturning() {
    when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response));
    var result = model().call(prompt);
    assertThat(result).isNotNull();
    assertThat(result.getResult().getOutput().getText()).isEqualTo("done");
    assertThat(admitted).hasValue(1);
    assertThat(released).hasValue(1);
  }

  @Test
  void admissionFailureNeverInvokesTheModel() {
    var calls = new AtomicInteger();
    when(delegate.stream(any(Prompt.class)))
        .thenAnswer(ignored -> {
          calls.incrementAndGet();
          return Flux.just(response);
        });
    var model =
        new PlanRequestChatModel(
            delegate,
            () -> {
              throw new IllegalStateException("busy fixture");
            });
    assertThatThrownBy(() -> model.stream(prompt).blockLast(Duration.ofSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("busy fixture");
    assertThat(calls).hasValue(0);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"confirmed", "unavailable", "generic", "catalog"})
  void safeFailureObservationPrecedesSlotReleaseAndDoesNotInventAppUsageProof(String kind) {
    Throwable source =
        switch (kind) {
          case "confirmed" ->
              ResponsesTransportException.planUsageFailure(
                  "subscription_sharing_usage_limit_exceeded", null);
          case "unavailable" ->
              ResponsesTransportException.planUsageFailure(
                  "subscription_sharing_usage_unavailable", null);
          case "generic" ->
              new ResponsesTransportException(ResponsesTransportException.Category.RATE_LIMIT);
          default ->
              dev.mikoto2000.rei.chatgpt.ChatGptException.of(
                  dev.mikoto2000.rei.chatgpt.ChatGptException.Code.USAGE_LIMIT);
        };
    when(delegate.stream(any(Prompt.class))).thenReturn(Flux.error(source));
    var observed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var propagated = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var activeWhenObserved = new AtomicInteger(-1);
    var model =
        new PlanRequestChatModel(
            delegate,
            this::begin,
            failure -> {
              activeWhenObserved.set(active.get());
              observed.set(failure);
            });
    model.stream(prompt).subscribe(ignored -> {}, propagated::set);
    assertThat(observed.get())
        .isNotNull()
        .isSameAs(propagated.get())
        .isInstanceOf(ResponsesTransportException.class);
    var failure = (ResponsesTransportException) observed.get();
    assertThat(failure.confirmedAppUsageLimit()).isEqualTo(kind.equals("confirmed"));
    assertThat(failure.retryAllowed()).isFalse();
    assertThat(failure.getCause()).isNull();
    assertThat(activeWhenObserved).hasValue(1);
    assertThat(active).hasValue(0);
    assertThat(released).hasValue(1);
  }
}
