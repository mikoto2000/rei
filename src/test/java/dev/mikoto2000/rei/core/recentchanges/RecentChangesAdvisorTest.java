package dev.mikoto2000.rei.core.recentchanges;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** 直近変更一覧対話の補助の結果と状態の契約を検証するテスト。 */
class RecentChangesAdvisorTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** 直近の変更の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void recentChangesIsInjectedIntoUserMessage() {
    RecentChanges changes =
        new RecentChanges(20, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    changes.record("src/UserService.java", RecentChanges.OP_EDIT, "save() を Optional 化");

    RecentChangesAdvisor advisor = new RecentChangesAdvisor(changes);
    UserMessage userMessage = UserMessage.builder().text("続けて").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    String text = result.prompt().getUserMessage().getText();
    assertTrue(text.contains("## Recent Changes"));
    assertTrue(text.contains("src/UserService.java"));
    assertTrue(text.contains("save() を Optional 化"));
    assertTrue(text.contains("続けて"));
  }

  /** 直近の変更の把握で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void emptyRecentChangesLeavesMessageUnchanged() {
    RecentChanges changes =
        new RecentChanges(20, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    RecentChangesAdvisor advisor = new RecentChangesAdvisor(changes);
    UserMessage userMessage = UserMessage.builder().text("hello").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    assertEquals("hello", result.prompt().getUserMessage().getText());
  }

  /** 検証条件を再現するためのNoop対話の補助Chain。 */
  private static final class NoopAdvisorChain implements AdvisorChain {
    /** 検証用に、直近の変更の把握で参照する観測内容登録台帳を返す。 */
    @Override
    public io.micrometer.observation.ObservationRegistry getObservationRegistry() {
      return io.micrometer.observation.ObservationRegistry.NOOP;
    }
  }
}
