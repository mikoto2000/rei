package dev.mikoto2000.rei.core.actionplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** 操作計画対話の補助の結果と状態の契約を検証するテスト。 */
class ActionPlanAdvisorTest {

  /** 行動計画の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void actionPlanIsInjectedIntoUserMessage() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("再現テストを追加");
    plan.addStep("実装修正");
    plan.startStep("step-2");

    ActionPlanAdvisor advisor = new ActionPlanAdvisor(plan);
    UserMessage userMessage = UserMessage.builder().text("続けて").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    String text = result.prompt().getUserMessage().getText();
    assertTrue(text.contains("## Action Plan"));
    assertTrue(text.contains("2. [IN_PROGRESS] 実装修正"));
    assertTrue(text.contains("続けて"));
  }

  /** 行動計画の管理で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void emptyActionPlanLeavesMessageUnchanged() {
    ActionPlan plan = new ActionPlan();
    ActionPlanAdvisor advisor = new ActionPlanAdvisor(plan);
    UserMessage userMessage = UserMessage.builder().text("hello").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    assertEquals("hello", result.prompt().getUserMessage().getText());
  }

  /** 検証条件を再現するためのNoop対話の補助Chain。 */
  private static final class NoopAdvisorChain implements AdvisorChain {
    /** 検証用に、行動計画の管理で参照する観測内容登録台帳を返す。 */
    @Override
    public io.micrometer.observation.ObservationRegistry getObservationRegistry() {
      return io.micrometer.observation.ObservationRegistry.NOOP;
    }
  }
}
