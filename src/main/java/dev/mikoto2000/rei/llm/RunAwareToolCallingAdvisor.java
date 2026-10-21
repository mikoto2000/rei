package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import reactor.core.publisher.Flux;

/**
 * 用途に応じたモデル利用に必要な実行AwareツールCalling対話の補助を扱う。
 *
 * <p>Retains legacy tool calling while allowing Rei's run loop to own budgets and permissions.
 */
public final class RunAwareToolCallingAdvisor extends ToolCallingAdvisor {
  /** 既定の状態でRunAwareToolCallingAdvisorを初期化する。 */
  public RunAwareToolCallingAdvisor() {
    // The old model-owned loop ran beneath prompt and memory advisors; preserve that boundary.
    super(
        ToolCallingManager.builder().build(),
        DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER,
        org.springframework.core.Ordered.LOWEST_PRECEDENCE - 1,
        true);
  }

  /** 用途に応じたモデル利用で用いるadvise呼び出しを返す。 */
  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    return applicationOwnsTools(request)
        ? chain.nextCall(request)
        : super.adviseCall(request, chain);
  }

  /** 用途に応じたモデル利用で用いるadvise逐次データを返す。 */
  @Override
  public Flux<ChatClientResponse> adviseStream(
      ChatClientRequest request, StreamAdvisorChain chain) {
    return applicationOwnsTools(request)
        ? chain.nextStream(request)
        : super.adviseStream(request, chain);
  }

  /** 用途に応じたモデル利用において、アプリケーションOwnsツール一覧に該当するか判定する。 */
  private boolean applicationOwnsTools(ChatClientRequest request) {
    return request.prompt().getOptions() instanceof ToolCallingChatOptions options
        && options.getToolContext() != null
        && options.getToolContext().get(RunExecutionContext.KEY) instanceof RunExecutionContext;
  }
}
