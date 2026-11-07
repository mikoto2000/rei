package dev.mikoto2000.rei.core.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Task State がセッション（アプリケーション全体のシングルトン Bean）として保持されることを検証する。
 *
 * <p>Working Set と同じく、Task State はシングルトン Bean として保持され、 Advisor と Tools が同じインスタンスを共有する。
 */
@org.junit.jupiter.api.Tag("integration")
class TaskStateSessionTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void taskStateIsSingletonAcrossContext() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(TaskStateConfiguration.class, TestConfiguration.class);
      context.refresh();
      TaskState first = context.getBean(TaskState.class);
      TaskState second = context.getBean(TaskState.class);
      assertSame(first, second);
    }
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void advisorRendersTaskStateUpdatedThroughTools() {
    TaskState state = new TaskState();
    TaskStateAdvisor advisor = new TaskStateAdvisor(state);
    TaskStateTools tools = new TaskStateTools(state);
    assertEquals(
        "updated",
        tools.updateTaskState(
            "Verify shared task state",
            TaskState.STATUS_IN_PROGRESS,
            List.of(),
            List.of("Validate")));
    assertEquals("Verify shared task state", state.goal());
    var request = ChatClientRequest.builder().prompt(new Prompt("Continue the task")).build();
    assertTrue(
        advisor.before(request, null).prompt().getUserMessage().getText()
            .contains("Verify shared task state"));
  }

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration
  static class TestConfiguration {

    /** {@code clock} の条件を再現するための補助情報を返す。 */
    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE);
    }
  }
}
