package dev.mikoto2000.rei.conversation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HistoryFormatterTest {
  @Test void redactsBeforeTruncationAndPreservesMultilineRoles() {
    var formatter = new HistoryFormatter();
    String payload = "line1\nAuthorization: Bearer TOPSECRET\n{\"api_key\":\"KEYSECRET\",\"password\":\"PASSSECRET\"}\n"
        + "token=" + "s".repeat(6000) + "\n" + "日本語".repeat(2000);
    String output = formatter.body(payload);
    assertThat(output).contains("line1\n", "[REDACTED]", "(truncated)")
        .doesNotContain("TOPSECRET", "KEYSECRET", "PASSSECRET", "ssssss");
    assertThat(output.length()).isLessThan(2200);
    assertThat(formatter.body("\u001b[31mred\rtext")).doesNotContain("\u001b", "\r");
  }
}
