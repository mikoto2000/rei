package dev.mikoto2000.rei.workcontext;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/** 作業文脈の引き継ぎに必要な作業文脈対話の補助を扱う。 */
@Component
public class WorkContextAdvisor implements BaseAdvisor {
  /** サービス。 */
  private final WorkContextService service;

  /** この機能の動作設定。 */
  private final WorkContextProperties properties;

  /** Gitの情報。 */
  private final WorkContextGit git;

  /** 指定された値や利用先を使って、WorkContextAdvisorを初期化する。 */
  public WorkContextAdvisor(
      WorkContextService service, WorkContextProperties properties, WorkContextGit git) {
    this.service = service;
    this.properties = properties;
    this.git = git;
  }

  /** 作業文脈の引き継ぎの本処理へ渡す文脈を準備する。 */
  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    var owner = request.context().get(AgentRunContext.class.getName());
    if (!(owner instanceof AgentRunContext run) || run.projectId() == null) return request;
    try {
      var saved = service.current(run.projectId()).orElse(null);
      if (saved == null) return request;
      String text =
          new WorkContextFormatter()
              .context(
                  saved,
                  git.capture(run.projectRoot(), Instant.now()),
                  properties.maxContextTokens());
      if (text.isBlank()) return request;
      var messages = new ArrayList<Message>(request.prompt().getInstructions());
      messages.add(
          SystemMessage.builder().text(text).metadata(Map.of("rei.workContext", true)).build());
      return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
    } catch (RuntimeException error) {
      RunCancellation.propagate(error);
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn(
              "Work Context unavailable; continuing chat: {} /work show で保存状態を確認してください。",
              dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(error).display());
      return request;
    }
  }

  /** 作業文脈の引き継ぎの実行結果を受け取り、後続の処理へ反映する。 */
  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  @Override
  public int getOrder() {
    return -109;
  }
}
