package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mikoto2000.rei.event.CredentialRedactor;
import org.junit.jupiter.api.Test;

/** Slack認証情報秘密情報の除去の結果と状態の契約を検証するテスト。 */
class SlackCredentialRedactionTest {
  /** Slackとの連携で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void sharedEventAndHistoryRedactorRecognizesStandaloneSlackSecrets() {
    assertEquals(
        "bot [REDACTED] app [REDACTED]",
        CredentialRedactor.redact("bot xoxb-000-example app xapp-1-example"));
  }
}
