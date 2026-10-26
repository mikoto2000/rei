package dev.mikoto2000.rei.core.actionplan;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import java.util.ArrayList;
import java.util.List;
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

/** 各ユーザーターンの LLM 呼び出し前に、現在の Action Plan をコンテキストとして注入する。 */
@Component
public class ActionPlanAdvisor implements BaseAdvisor {

  /** 操作計画。 */
  private final ActionPlan actionPlan;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 行動計画の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ActionPlanAdvisor(ActionPlan actionPlan) {
    this(actionPlan, (AgentEventFactory) null, (AgentEventPublisher) null);
  }

  /** 行動計画の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public ActionPlanAdvisor(
      ActionPlan actionPlan,
      ObjectProvider<AgentEventFactory> events,
      ObjectProvider<AgentEventPublisher> eventPublisher) {
    this(actionPlan, events.getIfAvailable(), eventPublisher.getIfAvailable());
  }

  /** 指定された値や利用先を使って、ActionPlanAdvisorを初期化する。 */
  public ActionPlanAdvisor(
      ActionPlan actionPlan, AgentEventFactory events, AgentEventPublisher eventPublisher) {
    this.actionPlan = actionPlan;
    this.events = events;
    this.eventPublisher = eventPublisher;
  }

  /** 行動計画の管理で用いる操作計画を返す。 */
  ActionPlan actionPlan() {
    return actionPlan;
  }

  /** 行動計画の管理の本処理へ渡す文脈を準備する。 */
  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    if (!dev.mikoto2000.rei.context.CancelledTaskHistory.allowsState(
        request.context(), actionPlan.ownerRunId())) return request;
    UserMessage userMessage = request.prompt().getUserMessage();
    if (userMessage == null) {
      return request;
    }
    String context = actionPlan.renderForPrompt();
    if (context.isBlank()) {
      return request;
    }
    publishInjected(context);
    UserMessage contextualMessage =
        userMessage.mutate().text(context + "\n\n" + userMessage.getText()).build();
    Prompt prompt = request.prompt();
    return request
        .mutate()
        .prompt(
            new Prompt(
                replaceUserMessage(prompt.getInstructions(), userMessage, contextualMessage),
                prompt.getOptions()))
        .build();
  }

  /** 行動計画の管理の実行結果を受け取り、後続の処理へ反映する。 */
  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  @Override
  public int getOrder() {
    return -75;
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

  /** 実行状況を利用先で把握できるよう、追加されたを通知する。 */
  private void publishInjected(String context) {
    if (events != null && eventPublisher != null) {
      eventPublisher.publish(events.contextInjected("action_plan", null, context.length()));
    }
  }
}
