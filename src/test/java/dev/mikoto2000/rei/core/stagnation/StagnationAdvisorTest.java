package dev.mikoto2000.rei.core.stagnation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class StagnationAdvisorTest {

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

  private static final class NoopAdvisorChain implements AdvisorChain {
    @Override
    public io.micrometer.observation.ObservationRegistry getObservationRegistry() {
      return io.micrometer.observation.ObservationRegistry.NOOP;
    }
  }
}
