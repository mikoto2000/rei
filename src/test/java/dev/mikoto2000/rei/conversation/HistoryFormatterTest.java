package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 履歴表示形式への変換の結果と状態の契約を検証するテスト。 */
class HistoryFormatterTest {
  /** 会話履歴と受付記録の管理で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void redactsSecretsAndPreservesFullMultilineBody() {
    var formatter = new HistoryFormatter();
    String payload =
        "line1\nAuthorization: Bearer TOPSECRET\n{\"api_key\":\"KEYSECRET\",\"password\":\"PASSSECRET\"}\n"
            + "token="
            + "s".repeat(6000)
            + "\n"
            + "日本語".repeat(2000);
    String output = formatter.body(payload);
    assertThat(output)
        .contains("line1\n", "[REDACTED]", "日本語".repeat(2000))
        .doesNotContain("TOPSECRET", "KEYSECRET", "PASSSECRET", "ssssss", "(truncated)");
    assertThat(formatter.body("\u001b[31mred\rtext")).doesNotContain("\u001b", "\r");
  }
}
