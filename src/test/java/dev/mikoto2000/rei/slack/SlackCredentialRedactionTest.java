package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mikoto2000.rei.event.CredentialRedactor;
import org.junit.jupiter.api.Test;

class SlackCredentialRedactionTest {
  @Test
  void sharedEventAndHistoryRedactorRecognizesStandaloneSlackSecrets() {
    assertEquals(
        "bot [REDACTED] app [REDACTED]",
        CredentialRedactor.redact("bot xoxb-000-example app xapp-1-example"));
  }
}
