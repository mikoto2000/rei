package dev.mikoto2000.rei.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Bounded cooldown after transport/provider failures. Never retries a request or any tool. */
public final class ModelOutageGuard {
  private final Clock clock;
  private final Duration cooldown;
  private Instant retryAt;
  private boolean probing;
  private long generation;

  record Permit(long generation, boolean probe) {}

  ModelOutageGuard(Clock clock, Duration cooldown) {
    this.clock = clock;
    this.cooldown = cooldown;
  }

  synchronized Permit acquire() {
    if (retryAt != null) {
      if (clock.instant().isBefore(retryAt) || probing) throw new CoolingDown();
      probing = true;
      return new Permit(generation, true);
    }
    return new Permit(generation, false);
  }

  synchronized void completed(Permit permit) {
    if (permit.generation() != generation) return;
    retryAt = null;
    probing = false;
  }

  synchronized void failed(Permit permit, Throwable error) {
    if (permit.generation() != generation) return;
    if (!cooldown.isZero() && isOutage(error)) {
      retryAt = clock.instant().plus(cooldown);
      generation++;
      probing = false;
    } else cancelled(permit);
  }

  private static boolean isOutage(Throwable failure) {
    var causes = new java.util.ArrayList<Throwable>();
    var seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    for (var cause = failure;
        cause != null && causes.size() < 16 && seen.add(cause);
        cause = cause.getCause()) causes.add(cause);
    if (causes.stream()
        .anyMatch(
            c ->
                c instanceof InterruptedException
                    || c instanceof java.util.concurrent.CancellationException)) return false;
    for (var cause : causes) {
      if (cause instanceof java.net.SocketException
          || cause instanceof java.net.UnknownHostException
          || cause instanceof java.net.http.HttpTimeoutException
          || cause instanceof java.util.concurrent.TimeoutException
          || cause instanceof com.openai.errors.OpenAIIoException) return true;
      if (cause instanceof com.openai.errors.OpenAIServiceException response
          && (response.statusCode() >= 500
              || cause instanceof com.openai.errors.SseException
                  && response.statusCode() >= 200
                  && response.statusCode() < 300)) return true;
      if (cause
              instanceof
              org.springframework.web.reactive.function.client.WebClientResponseException response
          && response.getStatusCode().is5xxServerError()) return true;
    }
    return false;
  }

  synchronized void cancelled(Permit permit) {
    if (permit.generation() == generation && permit.probe()) probing = false;
  }

  /** Contains no provider body, endpoint, prompt or credentials. */
  public static final class CoolingDown extends IllegalStateException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    CoolingDown() {
      super("Model temporarily unavailable; cooldown in progress");
    }
  }
}
