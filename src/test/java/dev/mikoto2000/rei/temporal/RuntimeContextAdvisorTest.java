package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
  void prependsRequestStartTimeAndDirectsCurrentTimeQueriesToTool() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:35:42.152Z"), ZoneId.of("Asia/Tokyo"));
    RuntimeContextAdvisor advisor = new RuntimeContextAdvisor(fixed);
    ChatClientRequest request =
        ChatClientRequest.builder().prompt(new Prompt(new UserMessage("hello"))).build();

    ChatClientRequest actual = advisor.before(request, Mockito.mock(AdvisorChain.class));

    String text = actual.prompt().getUserMessage().getText();
    assertTrue(text.contains("# Runtime Context"));
    assertTrue(text.contains("Request start time: 2026-08-17T01:35:42.152+09:00"));
    assertFalse(text.contains("Current time:"));
    assertTrue(text.contains("fixed for this request"));
    assertTrue(text.contains("call getCurrentTime"));
    assertTrue(text.contains("Timezone: Asia/Tokyo"));
    assertTrue(text.endsWith("hello"));
    assertEquals("hello", request.prompt().getUserMessage().getText());
  }

  @Test
  void requestTimestampStaysFixedWhileTimeToolReadsFreshTime() {
    Clock clock = Mockito.mock(Clock.class);
    Mockito.when(clock.getZone()).thenReturn(ZoneId.of("Asia/Tokyo"));
    Mockito.when(clock.instant()).thenReturn(
        Instant.parse("2026-11-03T14:59:00Z"), Instant.parse("2026-11-03T15:01:00Z"));
    var advisor = new RuntimeContextAdvisor(clock);
    var request = ChatClientRequest.builder().prompt(new Prompt(new UserMessage("today"))).build();
    var initial = advisor.before(request, Mockito.mock(AdvisorChain.class));
    String startContext = initial.prompt().getUserMessage().getText();
    assertTrue(startContext.contains("Request start time: 2026-11-03T23:59+09:00"));
    assertEquals("2026-11-04T00:01+09:00", new ClockTools(clock).getCurrentTime().timestamp());
    assertEquals(startContext, initial.prompt().getUserMessage().getText());
    var nextRequest = advisor.before(request, Mockito.mock(AdvisorChain.class));
    assertTrue(nextRequest.prompt().getUserMessage().getText()
        .contains("Request start time: 2026-11-04T00:01+09:00"));
  }
}
