package dev.mikoto2000.rei.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.LlmRequestStartedPayload;
import reactor.core.publisher.Flux;

class AgentEventChatModelTest {

  @Test
  void publishesFeatureLifecycleForSynchronousCalls() {
    ChatModel delegate = mock(ChatModel.class);
    Prompt prompt = new Prompt("select a skill");
    ChatResponse response = mock(ChatResponse.class);
    when(delegate.call(prompt)).thenReturn(response);
    List<AgentEvent> events = new ArrayList<>();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);

    ChatResponse actual = new AgentEventChatModel(LlmFeature.AGENT_SKILLS, delegate,
        new AgentEventFactory(Clock.systemUTC()), bus).call(prompt);

    assertEquals(response, actual);
    assertEquals(AgentEventType.LLM_REQUEST_STARTED, events.get(0).type());
    assertEquals(LlmFeature.AGENT_SKILLS, ((LlmRequestStartedPayload) events.get(0).payload()).feature());
    assertEquals(AgentEventType.LLM_RESPONSE_COMPLETED, events.get(1).type());
  }

  @Test
  void publishesCompletionOnlyForSuccessfulStreams() {
    ChatModel delegate = mock(ChatModel.class);
    Prompt prompt = new Prompt("search");
    when(delegate.stream(prompt)).thenReturn(Flux.error(new IllegalStateException("boom")));
    List<AgentEvent> events = new ArrayList<>();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);

    new AgentEventChatModel(LlmFeature.SEARCH, delegate,
        new AgentEventFactory(Clock.systemUTC()), bus).stream(prompt).onErrorComplete().blockLast();

    assertEquals(AgentEventType.LLM_REQUEST_STARTED, events.getFirst().type());
    assertFalse(events.stream().anyMatch(event -> event.type() == AgentEventType.LLM_RESPONSE_COMPLETED));
  }

  @Test
  void publishesFailureForSynchronousCallErrors() {
    ChatModel delegate = mock(ChatModel.class);
    Prompt prompt = new Prompt("fail");
    when(delegate.call(prompt)).thenThrow(new IllegalStateException("boom"));
    List<AgentEvent> events = new ArrayList<>();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);

    assertThrows(IllegalStateException.class, () -> new AgentEventChatModel(LlmFeature.CHAT, delegate,
        new AgentEventFactory(Clock.systemUTC()), bus).call(prompt));

    assertEquals(AgentEventType.LLM_REQUEST_STARTED, events.get(0).type());
    assertEquals(AgentEventType.LLM_REQUEST_FAILED, events.get(1).type());
  }

  @Test
  void publishesFirstTokenForStreams() {
    ChatModel delegate = mock(ChatModel.class);
    Prompt prompt = new Prompt("stream");
    ChatResponse response = mock(ChatResponse.class);
    when(delegate.stream(prompt)).thenReturn(Flux.just(response, response));
    List<AgentEvent> events = new ArrayList<>();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);

    new AgentEventChatModel(LlmFeature.CHAT, delegate,
        new AgentEventFactory(Clock.systemUTC()), bus).stream(prompt).blockLast();

    assertEquals(AgentEventType.LLM_REQUEST_STARTED, events.get(0).type());
    assertEquals(AgentEventType.LLM_RESPONSE_FIRST_TOKEN, events.get(1).type());
    assertEquals(AgentEventType.LLM_RESPONSE_COMPLETED, events.get(2).type());
    assertEquals(3, events.size());
  }
}
