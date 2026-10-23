package dev.mikoto2000.rei.checkpoint;

import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.util.ArrayList;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * 作業の中断と再開に必要な再開文脈対話の補助を扱う。
 *
 * <p>Historical data is injected separately from the user's request and remains within
 * ContextAssembler's hard budget.
 */
@Component
public class ResumeContextAdvisor implements BaseAdvisor {
  /** サービス。 */
  private final PersistentCheckpointService service;

  /** 指定された値や利用先を使って、ResumeContextAdvisorを初期化する。 */
  public ResumeContextAdvisor(PersistentCheckpointService service) {
    this.service = service;
  }

  /** 作業の中断と再開の本処理へ渡す文脈を準備する。 */
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    var run = AgentRunScope.current();
    if (run == null) return request;
    var handoff = service.modelHandoff(run.runId());
    String context = handoff.text();
    if (context.isBlank()) return request;
    var messages = new ArrayList<Message>(request.prompt().getInstructions());
    var current = request.prompt().getUserMessage();
    // ContextHistoryAdvisor has already replayed logs/turns/legacy memory. Those immutable
    // records may contain obsolete host controls; they are not renewed user requests.
    messages.removeIf(
        m ->
            m != current
                && m instanceof org.springframework.ai.chat.messages.UserMessage
                && service.isSupersededResumeRequest(run.runId(), m.getText()));
    messages.addFirst(
        SystemMessage.builder()
            .text(context)
            .metadata(
                java.util.Map.of(
                    "rei.resumeContext",
                    true,
                    "rei.resumeRunId",
                    run.runId(),
                    "rei.knownFileInventory",
                    handoff.hasFileInventory()))
            .build());
    return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
  }

  /** 作業の中断と再開の実行結果を受け取り、後続の処理へ反映する。 */
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  public int getOrder() {
    return -66;
  }
}
