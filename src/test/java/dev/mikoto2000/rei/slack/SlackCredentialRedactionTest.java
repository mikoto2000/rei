package dev.mikoto2000.rei.slack;

import dev.mikoto2000.rei.event.CredentialRedactor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlackCredentialRedactionTest {
  @Test void sharedEventAndHistoryRedactorRecognizesStandaloneSlackSecrets() {
    assertEquals("bot [REDACTED] app [REDACTED]",CredentialRedactor.redact("bot xoxb-000-example app xapp-1-example"));
  }
}
