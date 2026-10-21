package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** 実行環境文脈対話の補助の結果と状態の契約を検証するテスト。 */
class RuntimeContextAdvisorTest {

  /** 時刻と期間の解釈で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void prependsCurrentRuntimeContextForEachRequest() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:35:42.152Z"), ZoneId.of("Asia/Tokyo"));
    RuntimeContextAdvisor advisor = new RuntimeContextAdvisor(fixed);
    ChatClientRequest request =
        ChatClientRequest.builder().prompt(new Prompt(new UserMessage("hello"))).build();

    ChatClientRequest actual = advisor.before(request, Mockito.mock(AdvisorChain.class));

    String text = actual.prompt().getUserMessage().getText();
    assertTrue(text.contains("# Runtime Context"));
    assertTrue(text.contains("Current time: 2026-08-17T01:35:42.152+09:00"));
    assertTrue(text.contains("Timezone: Asia/Tokyo"));
    assertTrue(text.endsWith("hello"));
  }
}
