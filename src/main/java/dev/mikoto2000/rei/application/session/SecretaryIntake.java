package dev.mikoto2000.rei.application.session;

import dev.mikoto2000.rei.secretary.SecretaryLedger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Lightweight new-input observer; failures do not reject admitted conversations. */
@Component
@ConditionalOnProperty(
    name = {"rei.secretary.enabled", "rei.secretary.extraction-enabled"},
    havingValue = "true")
public final class SecretaryIntake {
  private final SecretaryLedger ledger;

  public SecretaryIntake(SecretaryLedger ledger) {
    this.ledger = ledger;
  }

  @EventListener
  public void accepted(ConversationAccepted event) {
    if (event.text() == null || event.text().isBlank() || event.text().startsWith("/")) return;
    try {
      ledger.extract(
          event.projectId(),
          "conversation:" + event.sessionId() + "/" + event.runId(),
          event.text().substring(0, Math.min(32768, event.text().length())));
    } catch (RuntimeException failure) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Secretary intake failed: {}", failure.getClass().getSimpleName());
    }
  }
}
