package dev.mikoto2000.rei.core.filesummary;

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

/** 各ユーザーターンの LLM 呼び出し前に、有効な File Summary をコンテキストとして注入する。 */
@Component
public class FileSummaryAdvisor implements BaseAdvisor {

  /** ファイル要約キャッシュ。 */
  private final FileSummaryCache fileSummaryCache;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** ファイル内容の要約を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public FileSummaryAdvisor(FileSummaryCache fileSummaryCache) {
    this(fileSummaryCache, (AgentEventFactory) null, (AgentEventPublisher) null);
  }

  /** ファイル内容の要約を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public FileSummaryAdvisor(
      FileSummaryCache fileSummaryCache,
      ObjectProvider<AgentEventFactory> events,
      ObjectProvider<AgentEventPublisher> eventPublisher) {
    this(fileSummaryCache, events.getIfAvailable(), eventPublisher.getIfAvailable());
  }

  /** 指定された値や利用先を使って、FileSummaryAdvisorを初期化する。 */
  public FileSummaryAdvisor(
      FileSummaryCache fileSummaryCache,
      AgentEventFactory events,
      AgentEventPublisher eventPublisher) {
    this.fileSummaryCache = fileSummaryCache;
    this.events = events;
    this.eventPublisher = eventPublisher;
  }

  /** ファイル内容の要約で用いるファイル要約キャッシュを返す。 */
  FileSummaryCache fileSummaryCache() {
    return fileSummaryCache;
  }

  /** ファイル内容の要約の本処理へ渡す文脈を準備する。 */
  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    UserMessage userMessage = request.prompt().getUserMessage();
    if (userMessage == null) {
      return request;
    }
    String context = fileSummaryCache.renderForPrompt(path -> currentVersion(path));
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

  /** ファイル内容の要約の実行結果を受け取り、後続の処理へ反映する。 */
  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  @Override
  public int getOrder() {
    return -60;
  }

  /** ファイル内容の要約で用いる現在形式のバージョンを返す。 */
  private String currentVersion(String path) {
    try {
      java.nio.file.Path p = java.nio.file.Paths.get(path);
      if (!java.nio.file.Files.exists(p)) {
        return null;
      }
      return sha256(p);
    } catch (Exception e) {
      return null;
    }
  }

  /** ファイル内容の要約で用いる内容の同一性を照合するSHA-256ハッシュを返す。 */
  private String sha256(java.nio.file.Path path) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    byte[] bytes = java.nio.file.Files.readAllBytes(path);
    byte[] hash = digest.digest(bytes);
    StringBuilder sb = new StringBuilder();
    for (byte b : hash) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
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
      eventPublisher.publish(events.contextInjected("file_summaries", null, context.length()));
    }
  }
}
