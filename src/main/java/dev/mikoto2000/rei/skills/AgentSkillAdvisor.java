package dev.mikoto2000.rei.skills;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.SkillCandidatesEvaluatedPayload;
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

/** スキルの利用に必要なエージェントスキル対話の補助を扱う。 */
@Component
public class AgentSkillAdvisor implements BaseAdvisor {

  /** 振り分け文脈キー。 */
  public static final String ROUTING_CONTEXT_KEY =
      AgentSkillAdvisor.class.getName() + ".routingContext";

  /** 選択内容サービス。 */
  private final AgentSkillSelectionService selectionService;

  /** プロンプト表示への変換。 */
  private final AgentSkillPromptRenderer promptRenderer;

  /** 保存と検索の窓口。 */
  private final AgentSkillRepository repository;

  /** 候補統計。 */
  private final SkillCandidateStatistics candidateStatistics;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** nano時刻。 */
  private final LongSupplier nanoTime;

  /** スキルの利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AgentSkillAdvisor(
      AgentSkillSelectionService selectionService, AgentSkillPromptRenderer promptRenderer) {
    this(
        selectionService,
        promptRenderer,
        null,
        null,
        new AgentEventFactory(java.time.Clock.systemDefaultZone()),
        new InMemoryAgentEventBus(),
        System::nanoTime);
  }

  /** スキルの利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AgentSkillAdvisor(
      AgentSkillSelectionService selectionService,
      AgentSkillPromptRenderer promptRenderer,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this(
        selectionService,
        promptRenderer,
        null,
        null,
        eventFactory,
        eventPublisher,
        System::nanoTime);
  }

  /** スキルの利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @org.springframework.beans.factory.annotation.Autowired
  public AgentSkillAdvisor(
      AgentSkillSelectionService selectionService,
      AgentSkillPromptRenderer promptRenderer,
      AgentSkillRepository repository,
      SkillCandidateStatistics candidateStatistics,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this(
        selectionService,
        promptRenderer,
        repository,
        candidateStatistics,
        eventFactory,
        eventPublisher,
        System::nanoTime);
  }

  /** スキルの利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  AgentSkillAdvisor(
      AgentSkillSelectionService selectionService,
      AgentSkillPromptRenderer promptRenderer,
      AgentSkillRepository repository,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher,
      LongSupplier nanoTime) {
    this(
        selectionService, promptRenderer, repository, null, eventFactory, eventPublisher, nanoTime);
  }

  /** 指定された値や利用先を使って、AgentSkillAdvisorを初期化する。 */
  AgentSkillAdvisor(
      AgentSkillSelectionService selectionService,
      AgentSkillPromptRenderer promptRenderer,
      AgentSkillRepository repository,
      SkillCandidateStatistics candidateStatistics,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher,
      LongSupplier nanoTime) {
    this.selectionService = selectionService;
    this.promptRenderer = promptRenderer;
    this.repository = repository;
    this.candidateStatistics = candidateStatistics;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
    this.nanoTime = nanoTime;
  }

  /** スキルの利用の本処理へ渡す文脈を準備する。 */
  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    Prompt prompt = request.prompt();
    dev.mikoto2000.rei.core.chat.RunCancellation.checkActive(prompt);
    UserMessage userMessage = prompt.getUserMessage();
    if (userMessage == null) {
      return request;
    }

    SkillRoutingRunContext routingContext =
        request.context().get(ROUTING_CONTEXT_KEY) instanceof SkillRoutingRunContext context
            ? context
            : null;
    String runId = routingContext == null ? null : routingContext.runId();
    int routingInvocation = routingContext == null ? 1 : routingContext.nextInvocation();
    long startedAtNanos = nanoTime.getAsLong();
    List<AgentSkill> allSkills = repository == null ? List.of() : repository.findEnabled();
    int candidateCount = allSkills.size();
    String routingId = UUID.randomUUID().toString();
    eventPublisher.publish(
        eventFactory.skillRoutingStarted(runId, routingId, candidateCount, routingInvocation));
    AgentSkillSelection selection;
    try {
      var execution =
          prompt.getOptions()
                      instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
                  && options.getToolContext() != null
                  && options
                          .getToolContext()
                          .get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY)
                      instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext context
              ? context
              : null;
      selection =
          execution == null
              ? selectionService.select(userMessage.getText())
              : selectionService.select(userMessage.getText(), execution.modelCallBudget());
      dev.mikoto2000.rei.core.chat.RunCancellation.checkActive(prompt);
      java.util.List<String> selectedNames = skillNames(selection.selectedSkills());
      publishCandidateEvaluation(
          selection,
          allSkills.size(),
          selectedNames.isEmpty() ? null : selectedNames.getFirst(),
          runId,
          routingId);
      eventPublisher.publish(
          eventFactory.skillRoutingCompleted(
              runId,
              routingId,
              elapsedMillis(startedAtNanos),
              candidateCount,
              selectedNames.isEmpty() ? null : selectedNames.getFirst(),
              routingInvocation,
              selection.selectorDurationMs(),
              null,
              null,
              skillNames(selection.explicitSkills()),
              skillNames(selection.implicitSkills()),
              selection.warnings()));
    } catch (RuntimeException exception) {
      eventPublisher.publish(
          eventFactory.skillRoutingFailed(
              runId,
              routingId,
              elapsedMillis(startedAtNanos),
              candidateCount,
              routingInvocation,
              ErrorInformation.from(exception)));
      throw exception;
    }
    if (selection.selectedSkills().isEmpty()
        && selection.sanitizedPrompt().equals(userMessage.getText())) {
      return request;
    }

    String renderedText =
        promptRenderer.render(selection.sanitizedPrompt(), selection.selectedSkills());
    UserMessage renderedUserMessage = userMessage.mutate().text(renderedText).build();

    Prompt renderedPrompt =
        new Prompt(
            replaceUserMessage(prompt.getInstructions(), userMessage, renderedUserMessage),
            prompt.getOptions());
    return request.mutate().prompt(renderedPrompt).build();
  }

  /** 実行状況を利用先で把握できるよう、候補評価を通知する。 */
  private void publishCandidateEvaluation(
      AgentSkillSelection selection,
      int totalSkillCount,
      String actualSelectedSkill,
      String runId,
      String routingId) {
    if (selection.candidateDurationMs() == null) return;
    SkillCandidateEvaluation evaluation =
        SkillCandidateEvaluator.evaluate(selection.candidates(), actualSelectedSkill);
    if (candidateStatistics != null) candidateStatistics.record(evaluation);
    List<SkillCandidatesEvaluatedPayload.CandidateScore> topCandidates =
        selection.candidates().stream()
            .map(
                candidate ->
                    new SkillCandidatesEvaluatedPayload.CandidateScore(
                        candidate.skill().name(), candidate.score()))
            .toList();
    eventPublisher.publish(
        eventFactory.skillCandidatesEvaluated(
            runId,
            routingId,
            totalSkillCount,
            selection.candidateDurationMs(),
            actualSelectedSkill,
            evaluation.selected(),
            evaluation.top1Hit(),
            evaluation.top3Hit(),
            evaluation.top5Hit(),
            topCandidates));
  }

  /** スキルの利用の実行結果を受け取り、後続の処理へ反映する。 */
  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  @Override
  public int getOrder() {
    return 0;
  }

  /** 指定された内容へ切り替えるため、利用者メッセージを置き換える。 */
  private List<Message> replaceUserMessage(
      List<Message> messages, UserMessage original, UserMessage replacement) {
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

  /** スキルの利用で用いるスキル名前一覧を返す。 */
  private List<String> skillNames(List<AgentSkill> skills) {
    return skills.stream().map(AgentSkill::name).toList();
  }

  /** スキルの利用で用いる経過時間ミリ秒数を返す。 */
  private long elapsedMillis(long startedAtNanos) {
    return Math.max(0L, (nanoTime.getAsLong() - startedAtNanos) / 1_000_000L);
  }
}
