package dev.mikoto2000.rei.core.working;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.WorkingSetContextInjectedPayload;

class WorkingSetAdvisorTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  @Test
  void workingSetIsInjectedIntoUserMessage() {
    Clock clock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE);
    WorkingSet ws = new WorkingSet(20, clock);
    ws.recordRead(Path.of("src/foo.java"));
    ws.recordRead(Path.of("src/fooTest.java"));
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(received::add);

    WorkingSetAdvisor advisor = new WorkingSetAdvisor(ws, new AgentEventFactory(clock), bus);
    UserMessage userMessage = UserMessage.builder().text("fix the bug").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    String text = result.prompt().getUserMessage().getText();
    assertTrue(text.contains(Path.of("src/foo.java").toAbsolutePath().normalize().toString()));
    assertTrue(text.contains(Path.of("src/fooTest.java").toAbsolutePath().normalize().toString()));
    assertTrue(text.contains("fix the bug"));
    assertEquals(1, received.size());
    assertEquals(AgentEventType.WORKING_SET_CONTEXT_INJECTED, received.getFirst().type());
    WorkingSetContextInjectedPayload payload = (WorkingSetContextInjectedPayload) received.getFirst().payload();
    assertEquals(2, payload.itemCount());
    assertTrue(payload.contextCharacters() > 0);
  }

  @Test
  void emptyWorkingSetLeavesMessageUnchanged() {
    WorkingSet ws = new WorkingSet(20, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    WorkingSetAdvisor advisor = new WorkingSetAdvisor(ws);
    UserMessage userMessage = UserMessage.builder().text("hello").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    assertEquals("hello", result.prompt().getUserMessage().getText());
  }

  @Test
  void emptyWorkingSetDoesNotPublishContextInjectedEvent() {
    Clock clock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE);
    WorkingSet ws = new WorkingSet(20, clock);
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(received::add);
    WorkingSetAdvisor advisor = new WorkingSetAdvisor(ws, new AgentEventFactory(clock), bus);
    UserMessage userMessage = UserMessage.builder().text("hello").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    advisor.before(request, new NoopAdvisorChain());

    assertTrue(received.isEmpty());
  }

  private static final class NoopAdvisorChain implements AdvisorChain {
    @Override
    public io.micrometer.observation.ObservationRegistry getObservationRegistry() {
      return io.micrometer.observation.ObservationRegistry.NOOP;
    }
  }
}
