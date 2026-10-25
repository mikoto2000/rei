package dev.mikoto2000.rei.secretaryevents;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.GitHubLifecyclePayload;
import dev.mikoto2000.rei.secretary.SecretaryConnections;
import dev.mikoto2000.rei.secretary.SecretaryProvider;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Reuses authenticated, owner-scoped GitHub webhook facts without trusting payload prose. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SecretaryExternalEvents {
  private final AgentEventBus.Subscription subscription;
  private final java.util.concurrent.ThreadPoolExecutor worker =
      new java.util.concurrent.ThreadPoolExecutor(
          1,
          1,
          0,
          java.util.concurrent.TimeUnit.SECONDS,
          new java.util.concurrent.ArrayBlockingQueue<>(32),
          r -> {
            var t = new Thread(r, "secretary-webhook-evidence");
            t.setDaemon(true);
            return t;
          });

  public SecretaryExternalEvents(AgentEventBus bus, SecretaryConnections connections) {
    subscription =
        bus.subscribe(
            event -> {
              if (event.projectId() == null
                  || event.sessionId() == null
                  || !(event.payload() instanceof GitHubLifecyclePayload payload)) return;
              var f = payload.fact();
              if (f == null || f.repository() == null) return;
              String object =
                  f.pullRequest() == null ? "branch:" + f.branch() : "pr:" + f.pullRequest();
              try {
                worker.execute(
                    () ->
                        connections.ingestSigned(
                            event.projectId(),
                            event.sessionId(),
                            SecretaryProvider.Service.GITHUB,
                            f.repository(),
                            new SecretaryProvider.Item(
                                "webhook:" + object + ":" + f.type(),
                                payload.deliveryId(),
                                "GITHUB_FACT",
                                f.repository() + " " + object,
                                "",
                                f.conclusion() == null
                                    ? f.action() == null ? f.type() : f.action()
                                    : f.conclusion(),
                                f.occurredAt(),
                                null,
                                false,
                                Map.of(
                                    "source",
                                    "signed_webhook",
                                    "headSha",
                                    f.headSha() == null ? "" : f.headSha()))));
              } catch (java.util.concurrent.RejectedExecutionException saturated) {
                /* Durable original facts remain in the webhook repository. */
              }
            });
  }

  @jakarta.annotation.PreDestroy
  public void close() {
    subscription.unsubscribe();
    worker.shutdownNow();
  }
}
