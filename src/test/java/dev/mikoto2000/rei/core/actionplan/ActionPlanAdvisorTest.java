package dev.mikoto2000.rei.core.actionplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class ActionPlanAdvisorTest {

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

  private static final class NoopAdvisorChain implements AdvisorChain {
    @Override
    public io.micrometer.observation.ObservationRegistry getObservationRegistry() {
      return io.micrometer.observation.ObservationRegistry.NOOP;
    }
  }
}
