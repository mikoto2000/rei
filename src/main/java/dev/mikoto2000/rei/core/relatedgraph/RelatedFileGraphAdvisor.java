package dev.mikoto2000.rei.core.relatedgraph;

import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

/** 各ユーザーターンの LLM 呼び出し前に、Working Set に関連する Related Files をコンテキストとして注入する。 */
@Component
public class RelatedFileGraphAdvisor implements BaseAdvisor {

  /** 関連ファイル関連図。 */
  private final RelatedFileGraph relatedFileGraph;

  /** 作業中集合。 */
  private final WorkingSet workingSet;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 関連情報の探索を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public RelatedFileGraphAdvisor(RelatedFileGraph relatedFileGraph, WorkingSet workingSet) {
    this(relatedFileGraph, workingSet, (AgentEventFactory) null, (AgentEventPublisher) null);
  }

  /** 関連情報の探索を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public RelatedFileGraphAdvisor(
      RelatedFileGraph relatedFileGraph,
      WorkingSet workingSet,
      ObjectProvider<AgentEventFactory> events,
      ObjectProvider<AgentEventPublisher> eventPublisher) {
    this(relatedFileGraph, workingSet, events.getIfAvailable(), eventPublisher.getIfAvailable());
  }

  /** 指定された値や利用先を使って、RelatedFileGraphAdvisorを初期化する。 */
  public RelatedFileGraphAdvisor(
      RelatedFileGraph relatedFileGraph,
      WorkingSet workingSet,
      AgentEventFactory events,
      AgentEventPublisher eventPublisher) {
    this.relatedFileGraph = relatedFileGraph;
    this.workingSet = workingSet;
    this.events = events;
    this.eventPublisher = eventPublisher;
  }

  /** 関連情報の探索で用いる関連ファイル関連図を返す。 */
  RelatedFileGraph relatedFileGraph() {
    return relatedFileGraph;
  }

  /** 関連情報の探索の本処理へ渡す文脈を準備する。 */
  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    UserMessage userMessage = request.prompt().getUserMessage();
    if (userMessage == null) {
      return request;
    }
    Set<String> workingSetPaths =
        workingSet.getFiles().stream()
            .map(ref -> ref.path())
            .collect(java.util.stream.Collectors.toSet());
    String context = relatedFileGraph.renderForPrompt(workingSetPaths);
    if (context.isBlank()) {
      return request;
    }
    String rendered = "## Related Files\n\n" + context;
    publishInjected(rendered);
    UserMessage contextualMessage =
        userMessage.mutate().text(rendered + "\n\n" + userMessage.getText()).build();
    Prompt prompt = request.prompt();
    return request
        .mutate()
        .prompt(
            new Prompt(
                replaceUserMessage(prompt.getInstructions(), userMessage, contextualMessage),
                prompt.getOptions()))
        .build();
  }

  /** 関連情報の探索の実行結果を受け取り、後続の処理へ反映する。 */
  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  @Override
  public int getOrder() {
    return -50;
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
      eventPublisher.publish(events.contextInjected("related_files", null, context.length()));
    }
  }
}
