package dev.mikoto2000.rei.core.taskstate;

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

/** 各ユーザーターンの LLM 呼び出し前に、現在の Task State をコンテキストとして注入する。 */
@Component
public class TaskStateAdvisor implements BaseAdvisor {

  /** タスク状態。 */
  private final TaskState taskState;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 作業状態の記録を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public TaskStateAdvisor(TaskState taskState) {
    this(taskState, (AgentEventFactory) null, (AgentEventPublisher) null);
  }

  /** 作業状態の記録を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public TaskStateAdvisor(
      TaskState taskState,
      ObjectProvider<AgentEventFactory> events,
      ObjectProvider<AgentEventPublisher> eventPublisher) {
    this(taskState, events.getIfAvailable(), eventPublisher.getIfAvailable());
  }

  /** 指定された値や利用先を使って、TaskStateAdvisorを初期化する。 */
  public TaskStateAdvisor(
      TaskState taskState, AgentEventFactory events, AgentEventPublisher eventPublisher) {
    this.taskState = taskState;
    this.events = events;
    this.eventPublisher = eventPublisher;
  }

  /** 作業状態の記録で用いるタスク状態を返す。 */
  TaskState taskState() {
    return taskState;
  }

  /** 作業状態の記録の本処理へ渡す文脈を準備する。 */
  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    UserMessage userMessage = request.prompt().getUserMessage();
    if (userMessage == null) {
      return request;
    }
    String taskStateContext = taskState.renderForPrompt();
    if (taskStateContext.isBlank()) {
      return request;
    }
    publishInjected(taskStateContext);
    UserMessage contextualMessage =
        userMessage.mutate().text(taskStateContext + "\n\n" + userMessage.getText()).build();
    Prompt prompt = request.prompt();
    return request
        .mutate()
        .prompt(
            new Prompt(
                replaceUserMessage(prompt.getInstructions(), userMessage, contextualMessage),
                prompt.getOptions()))
        .build();
  }

  /** 作業状態の記録の実行結果を受け取り、後続の処理へ反映する。 */
  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  @Override
  public int getOrder() {
    return -80;
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
      eventPublisher.publish(events.contextInjected("task_state", null, context.length()));
    }
  }
}
