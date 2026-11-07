package dev.mikoto2000.rei.core.working;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;
import java.util.ArrayList;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 各ユーザーターンの LLM 呼び出し前に、現在の Working Set をコンテキストとして注入する。 */
@Component
public class WorkingSetAdvisor implements BaseAdvisor {

  /** 作業中集合。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      matchType = SuppressMatchType.EXACT,
      justification = "Uses the shared working set so callers see the current file selection.")
  private final WorkingSet workingSet;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 作業中の情報管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WorkingSetAdvisor(WorkingSet workingSet) {
    this(workingSet, (AgentEventFactory) null, (AgentEventPublisher) null);
  }

  /** 作業中の情報管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public WorkingSetAdvisor(
      WorkingSet workingSet,
      ObjectProvider<AgentEventFactory> events,
      ObjectProvider<AgentEventPublisher> eventPublisher) {
    this(workingSet, events.getIfAvailable(), eventPublisher.getIfAvailable());
  }

  /** 指定された値や利用先を使って、WorkingSetAdvisorを初期化する。 */
  public WorkingSetAdvisor(
      WorkingSet workingSet, AgentEventFactory events, AgentEventPublisher eventPublisher) {
    this.workingSet = workingSet;
    this.events = events;
    this.eventPublisher = eventPublisher;
  }

  /** 作業中の情報管理の本処理へ渡す文脈を準備する。 */
  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    UserMessage userMessage = request.prompt().getUserMessage();
    if (userMessage == null) {
      return request;
    }
    String workingSetContext = workingSet.renderForPrompt();
    if (workingSetContext.isBlank()
        && !Boolean.TRUE.equals(request.context().get("rei.contextProjection"))) return request;
    if (!workingSetContext.isBlank()) publishContextInjected(workingSetContext);
    var messages = new ArrayList<Message>(request.prompt().getInstructions());
    messages.removeIf(m -> Boolean.TRUE.equals(m.getMetadata().get("rei.workingSet")));
    messages.add(
        org.springframework.ai.chat.messages.SystemMessage.builder()
            .text(workingSetContext)
            .metadata(java.util.Map.of("rei.workingSet", true))
            .build());
    return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
  }

  /** 作業中の情報管理の実行結果を受け取り、後続の処理へ反映する。 */
  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  @Override
  public int getOrder() {
    return -90;
  }

  /** 実行状況を利用先で把握できるよう、文脈追加されたを通知する。 */
  private void publishContextInjected(String workingSetContext) {
    if (events == null || eventPublisher == null) {
      return;
    }
    eventPublisher.publish(
        events.workingSetContextInjected(workingSet.getFiles().size(), workingSetContext.length()));
  }
}
