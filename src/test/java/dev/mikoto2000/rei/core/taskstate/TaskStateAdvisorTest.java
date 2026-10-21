package dev.mikoto2000.rei.core.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** タスク状態対話の補助の結果と状態の契約を検証するテスト。 */
class TaskStateAdvisorTest {

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void taskStateIsInjectedIntoUserMessage() {
    TaskState state = new TaskState();
    state.start("UserService の null handling を修正する");
    state.addCompleted("再現テストを追加した");
    state.addPending("実装修正");

    TaskStateAdvisor advisor = new TaskStateAdvisor(state);
    UserMessage userMessage = UserMessage.builder().text("続けて").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    String text = result.prompt().getUserMessage().getText();
    assertTrue(text.contains("## Current Task"));
    assertTrue(text.contains("UserService の null handling を修正する"));
    assertTrue(text.contains("再現テストを追加した"));
    assertTrue(text.contains("実装修正"));
    assertTrue(text.contains("続けて"));
  }

  /** 作業状態の記録で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void emptyTaskStateLeavesMessageUnchanged() {
    TaskState state = new TaskState();
    TaskStateAdvisor advisor = new TaskStateAdvisor(state);
    UserMessage userMessage = UserMessage.builder().text("hello").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    assertEquals("hello", result.prompt().getUserMessage().getText());
  }

  /** 検証条件を再現するためのNoop対話の補助Chain。 */
  private static final class NoopAdvisorChain implements AdvisorChain {
    /** 検証用に、作業状態の記録で参照する観測内容登録台帳を返す。 */
    @Override
    public io.micrometer.observation.ObservationRegistry getObservationRegistry() {
      return io.micrometer.observation.ObservationRegistry.NOOP;
    }
  }
}
