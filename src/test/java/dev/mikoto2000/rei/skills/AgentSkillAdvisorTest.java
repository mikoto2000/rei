package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.SkillRoutingCompletedPayload;
import dev.mikoto2000.rei.event.SkillRoutingFailedPayload;
import dev.mikoto2000.rei.event.SkillRoutingStartedPayload;

class AgentSkillAdvisorTest {

  @Test
  void injectsSelectedSkillIntoUserMessageText() {
    AgentSkill skill = skill("sample");
    AgentSkillSelectionService selectionService = Mockito.mock(AgentSkillSelectionService.class);
    when(selectionService.select("@skill:sample hello")).thenReturn(
        new AgentSkillSelection(List.of(skill), List.of(), List.of(), "hello"));
    AgentSkillAdvisor advisor = new AgentSkillAdvisor(selectionService, new AgentSkillPromptRenderer());
    ChatClientRequest request = request("@skill:sample hello");

    ChatClientRequest advised = advisor.before(request, Mockito.mock(AdvisorChain.class));

    assertThat(advised.prompt().getUserMessage().getText()).contains("## Skill: sample");
    assertThat(advised.prompt().getUserMessage().getText()).contains("sample instructions");
    assertThat(advised.prompt().getUserMessage().getText()).contains("--- User request ---\nhello");
    assertThat(advised.prompt().getUserMessage().getText()).doesNotContain("@skill:sample");
    assertThat(advised.prompt().getOptions()).isSameAs(request.prompt().getOptions());
  }

  @Test
  void emitsRoutingMetricsWithWarningsAndSelectedSkillNames() {
    AgentSkill skill = skill("sample");
    AgentSkillSelectionService selectionService = Mockito.mock(AgentSkillSelectionService.class);
    when(selectionService.select("@skill:missing hello")).thenReturn(
        new AgentSkillSelection(List.of(skill), List.of(), List.of("[warn] Skill が見つかりません: missing"), "hello"));
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new java.util.ArrayList<>();
    bus.subscribe(events::add);
    AgentSkillRepository repository = Mockito.mock(AgentSkillRepository.class);
    when(repository.findEnabled()).thenReturn(List.of(skill, skill("other")));
    java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
    AgentSkillAdvisor advisor = new AgentSkillAdvisor(selectionService, new AgentSkillPromptRenderer(), repository,
        new AgentEventFactory(Clock.systemUTC()), bus, () -> nanos.getAndAdd(12_000_000L));

    advisor.before(request("@skill:missing hello"), Mockito.mock(AdvisorChain.class));

    assertThat(events).extracting(AgentEvent::type)
        .containsExactly(AgentEventType.SKILL_ROUTING_STARTED, AgentEventType.SKILL_ROUTING_COMPLETED);
    SkillRoutingStartedPayload started = (SkillRoutingStartedPayload) events.getFirst().payload();
    assertThat(started.candidateCount()).isEqualTo(2);
    SkillRoutingCompletedPayload completed = (SkillRoutingCompletedPayload) events.getLast().payload();
    assertThat(completed.durationMs()).isEqualTo(12);
    assertThat(completed.candidateCount()).isEqualTo(2);
    assertThat(completed.selectedSkill()).isEqualTo("sample");
    assertThat(completed.explicitSkillNames()).containsExactly("sample");
    assertThat(completed.implicitSkillNames()).isEmpty();
    assertThat(completed.warnings()).containsExactly("[warn] Skill が見つかりません: missing");
    assertThat(events.getFirst().correlationId()).isEqualTo(events.getLast().correlationId());
  }

  @Test
  void emitsFailedEventWhenSelectionThrows() {
    AgentSkillSelectionService selectionService = Mockito.mock(AgentSkillSelectionService.class);
    when(selectionService.select("hello")).thenThrow(new IllegalStateException("selection unavailable"));
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new java.util.ArrayList<>();
    bus.subscribe(events::add);
    AgentSkillRepository repository = Mockito.mock(AgentSkillRepository.class);
    when(repository.findEnabled()).thenReturn(List.of(skill("candidate")));
    java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
    AgentSkillAdvisor advisor = new AgentSkillAdvisor(selectionService, new AgentSkillPromptRenderer(), repository,
        new AgentEventFactory(Clock.systemUTC()), bus, () -> nanos.getAndAdd(7_000_000L));

    org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
        () -> advisor.before(request("hello"), Mockito.mock(AdvisorChain.class)));

    assertThat(events).extracting(AgentEvent::type)
        .containsExactly(AgentEventType.SKILL_ROUTING_STARTED, AgentEventType.SKILL_ROUTING_FAILED);
    SkillRoutingFailedPayload failed = (SkillRoutingFailedPayload) events.getLast().payload();
    assertThat(failed.durationMs()).isEqualTo(7);
    assertThat(failed.candidateCount()).isEqualTo(1);
  }

  @Test
  void returnsOriginalRequestWhenNoSkillIsSelected() {
    AgentSkillSelectionService selectionService = Mockito.mock(AgentSkillSelectionService.class);
    when(selectionService.select("hello")).thenReturn(new AgentSkillSelection(List.of(), List.of(), List.of(), "hello"));
    AgentSkillAdvisor advisor = new AgentSkillAdvisor(selectionService, new AgentSkillPromptRenderer());
    ChatClientRequest request = request("hello");

    ChatClientRequest advised = advisor.before(request, Mockito.mock(AdvisorChain.class));

    assertThat(advised).isSameAs(request);
  }

  @Test
  void routingInvocationIncrementsWithinRunAndIsIndependentBetweenRuns() {
    AgentSkillSelectionService selectionService = Mockito.mock(AgentSkillSelectionService.class);
    when(selectionService.select("hello")).thenReturn(new AgentSkillSelection(List.of(), List.of(), List.of(), "hello"));
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new java.util.ArrayList<>();
    bus.subscribe(events::add);
    AgentSkillAdvisor advisor = new AgentSkillAdvisor(selectionService, new AgentSkillPromptRenderer(),
        Mockito.mock(AgentSkillRepository.class), new AgentEventFactory(Clock.systemUTC()), bus, System::nanoTime);
    SkillRoutingRunContext firstRun = new SkillRoutingRunContext("run-1");
    SkillRoutingRunContext secondRun = new SkillRoutingRunContext("run-2");

    advisor.before(request("hello", firstRun), Mockito.mock(AdvisorChain.class));
    advisor.before(request("hello", firstRun), Mockito.mock(AdvisorChain.class));
    advisor.before(request("hello", secondRun), Mockito.mock(AdvisorChain.class));

    assertThat(events.stream().filter(event -> event.type() == AgentEventType.SKILL_ROUTING_STARTED)
        .map(event -> (SkillRoutingStartedPayload) event.payload())
        .map(SkillRoutingStartedPayload::routingInvocation)).containsExactly(1, 2, 1);
    assertThat(events.stream().filter(event -> event.type() == AgentEventType.SKILL_ROUTING_STARTED)
        .map(AgentEvent::runId)).containsExactly("run-1", "run-1", "run-2");
  }

  private ChatClientRequest request(String text) {
    return request(text, null);
  }

  private ChatClientRequest request(String text, SkillRoutingRunContext routingContext) {
    Prompt prompt = new Prompt(UserMessage.builder()
        .text(text)
        .build(),
        OpenAiChatOptions.builder()
            .model("test-model")
            .build());
    return new ChatClientRequest(prompt, routingContext == null ? Map.of()
        : Map.of(AgentSkillAdvisor.ROUTING_CONTEXT_KEY, routingContext));
  }

  private AgentSkill skill(String name) {
    return new AgentSkill(name, name + " description", true, Path.of(name), Path.of(name).resolve("SKILL.md"),
        name + " instructions");
  }
}
