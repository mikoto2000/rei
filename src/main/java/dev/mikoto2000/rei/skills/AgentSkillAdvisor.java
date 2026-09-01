package dev.mikoto2000.rei.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.SkillCandidatesEvaluatedPayload;

@Component
public class AgentSkillAdvisor implements BaseAdvisor {

  public static final String ROUTING_CONTEXT_KEY = AgentSkillAdvisor.class.getName() + ".routingContext";

  private final AgentSkillSelectionService selectionService;
  private final AgentSkillPromptRenderer promptRenderer;
  private final AgentSkillRepository repository;
  private final SkillCandidateStatistics candidateStatistics;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;
  private final LongSupplier nanoTime;

  public AgentSkillAdvisor(AgentSkillSelectionService selectionService, AgentSkillPromptRenderer promptRenderer) {
    this(selectionService, promptRenderer, null, null, new AgentEventFactory(java.time.Clock.systemDefaultZone()),
        new InMemoryAgentEventBus(), System::nanoTime);
  }

  public AgentSkillAdvisor(AgentSkillSelectionService selectionService, AgentSkillPromptRenderer promptRenderer,
      AgentEventFactory eventFactory, AgentEventPublisher eventPublisher) {
    this(selectionService, promptRenderer, null, null, eventFactory, eventPublisher, System::nanoTime);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public AgentSkillAdvisor(AgentSkillSelectionService selectionService, AgentSkillPromptRenderer promptRenderer,
      AgentSkillRepository repository, SkillCandidateStatistics candidateStatistics,
      AgentEventFactory eventFactory, AgentEventPublisher eventPublisher) {
    this(selectionService, promptRenderer, repository, candidateStatistics, eventFactory,
        eventPublisher, System::nanoTime);
  }

  AgentSkillAdvisor(AgentSkillSelectionService selectionService, AgentSkillPromptRenderer promptRenderer,
      AgentSkillRepository repository, AgentEventFactory eventFactory, AgentEventPublisher eventPublisher,
      LongSupplier nanoTime) {
    this(selectionService, promptRenderer, repository, null, eventFactory, eventPublisher, nanoTime);
  }

  AgentSkillAdvisor(AgentSkillSelectionService selectionService, AgentSkillPromptRenderer promptRenderer,
      AgentSkillRepository repository, SkillCandidateStatistics candidateStatistics,
      AgentEventFactory eventFactory, AgentEventPublisher eventPublisher, LongSupplier nanoTime) {
    this.selectionService = selectionService;
    this.promptRenderer = promptRenderer;
    this.repository = repository;
    this.candidateStatistics = candidateStatistics;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
    this.nanoTime = nanoTime;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    Prompt prompt = request.prompt();
    UserMessage userMessage = prompt.getUserMessage();
    if (userMessage == null) {
      return request;
    }

    SkillRoutingRunContext routingContext = request.context().get(ROUTING_CONTEXT_KEY) instanceof SkillRoutingRunContext context
        ? context : null;
    String runId = routingContext == null ? null : routingContext.runId();
    int routingInvocation = routingContext == null ? 1 : routingContext.nextInvocation();
    long startedAtNanos = nanoTime.getAsLong();
    List<AgentSkill> allSkills = repository == null ? List.of() : repository.findEnabled();
    int candidateCount = allSkills.size();
    String routingId = UUID.randomUUID().toString();
    eventPublisher.publish(eventFactory.skillRoutingStarted(runId, routingId, candidateCount, routingInvocation));
    AgentSkillSelection selection;
    try {
      selection = selectionService.select(userMessage.getText());
      java.util.List<String> selectedNames = skillNames(selection.selectedSkills());
      publishCandidateEvaluation(selection, allSkills.size(),
          selectedNames.isEmpty() ? null : selectedNames.getFirst(), runId, routingId);
      eventPublisher.publish(eventFactory.skillRoutingCompleted(runId, routingId, elapsedMillis(startedAtNanos),
          candidateCount, selectedNames.isEmpty() ? null : selectedNames.getFirst(), routingInvocation,
          selection.selectorDurationMs(), null, null,
          skillNames(selection.explicitSkills()), skillNames(selection.implicitSkills()), selection.warnings()));
    } catch (RuntimeException exception) {
      eventPublisher.publish(eventFactory.skillRoutingFailed(runId, routingId, elapsedMillis(startedAtNanos),
          candidateCount, routingInvocation, ErrorInformation.from(exception)));
      throw exception;
    }
    if (selection.selectedSkills().isEmpty() && selection.sanitizedPrompt().equals(userMessage.getText())) {
      return request;
    }

    String renderedText = promptRenderer.render(selection.sanitizedPrompt(), selection.selectedSkills());
    UserMessage renderedUserMessage = userMessage.mutate()
        .text(renderedText)
        .build();

    Prompt renderedPrompt = new Prompt(replaceUserMessage(prompt.getInstructions(), userMessage, renderedUserMessage),
        prompt.getOptions());
    return request.mutate()
        .prompt(renderedPrompt)
        .build();
  }

  private void publishCandidateEvaluation(AgentSkillSelection selection, int totalSkillCount,
      String actualSelectedSkill, String runId, String routingId) {
    if (selection.candidateDurationMs() == null) return;
    SkillCandidateEvaluation evaluation = SkillCandidateEvaluator.evaluate(selection.candidates(), actualSelectedSkill);
    if (candidateStatistics != null) candidateStatistics.record(evaluation);
    List<SkillCandidatesEvaluatedPayload.CandidateScore> topCandidates = selection.candidates().stream()
        .map(candidate -> new SkillCandidatesEvaluatedPayload.CandidateScore(candidate.skill().name(), candidate.score()))
        .toList();
    eventPublisher.publish(eventFactory.skillCandidatesEvaluated(runId, routingId, totalSkillCount,
        selection.candidateDurationMs(), actualSelectedSkill, evaluation.selected(), evaluation.top1Hit(),
        evaluation.top3Hit(), evaluation.top5Hit(), topCandidates));
  }

  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  @Override
  public int getOrder() {
    return 0;
  }

  private List<Message> replaceUserMessage(List<Message> messages, UserMessage original, UserMessage replacement) {
    List<Message> replaced = new ArrayList<>(messages.size());
    boolean replacedFirst = false;
    for (Message message : messages) {
      if (!replacedFirst && message == original) {
        replaced.add(replacement);
        replacedFirst = true;
      } else {
        replaced.add(message);
      }
    }
    return List.copyOf(replaced);
  }

  private List<String> skillNames(List<AgentSkill> skills) {
    return skills.stream()
        .map(AgentSkill::name)
        .toList();
  }

  private long elapsedMillis(long startedAtNanos) {
    return Math.max(0L, (nanoTime.getAsLong() - startedAtNanos) / 1_000_000L);
  }
}
