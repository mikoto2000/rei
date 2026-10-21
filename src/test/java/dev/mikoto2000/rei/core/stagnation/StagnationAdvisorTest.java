package dev.mikoto2000.rei.core.stagnation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** 停滞対話の補助の結果と状態の契約を検証するテスト。 */
class StagnationAdvisorTest {

  /** 実行の停滞への対処で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void replanNoticeIsInjectedWhenRequested() {
    StagnationDetector detector = new StagnationDetector(2);
    detector.recordIteration(false);
    detector.recordIteration(false);
    assertTrue(detector.isReplanRequested());

    StagnationAdvisor advisor = new StagnationAdvisor(detector);
    UserMessage userMessage = UserMessage.builder().text("続けて").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    String text = result.prompt().getUserMessage().getText();
    assertTrue(text.contains("## Replan Notice"));
    assertTrue(text.contains("続けて"));
  }

  /** 実行の停滞への対処で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void noReplanNoticeWhenNotRequested() {
    StagnationDetector detector = new StagnationDetector(4);
    StagnationAdvisor advisor = new StagnationAdvisor(detector);
    UserMessage userMessage = UserMessage.builder().text("hello").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    assertEquals("hello", result.prompt().getUserMessage().getText());
  }

  /** 検証条件を再現するためのNoop対話の補助Chain。 */
  private static final class NoopAdvisorChain implements AdvisorChain {
    /** 検証用に、実行の停滞への対処で参照する観測内容登録台帳を返す。 */
    @Override
    public io.micrometer.observation.ObservationRegistry getObservationRegistry() {
      return io.micrometer.observation.ObservationRegistry.NOOP;
    }
  }
}
