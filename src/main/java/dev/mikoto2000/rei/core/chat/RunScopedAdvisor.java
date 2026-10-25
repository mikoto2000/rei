package dev.mikoto2000.rei.core.chat;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;

/**
 * 会話の実行に必要な実行対象を限定した対話の補助を扱う。
 *
 * <p>Restores ownership on the advisor's scheduler without changing existing advisor
 * implementations.
 */
public final class RunScopedAdvisor implements BaseAdvisor {
  /** 委譲先。 */
  private final BaseAdvisor delegate;

  /** 指定された値や利用先を使って、RunScopedAdvisorを初期化する。 */
  public RunScopedAdvisor(BaseAdvisor delegate) {
    this.delegate = delegate;
  }

  /** 会話の実行で用いる委譲先を返す。 */
  public BaseAdvisor delegate() {
    return delegate;
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  public int getOrder() {
    return delegate.getOrder();
  }

  /** 会話の実行で参照する名前を返す。 */
  public String getName() {
    return delegate.getName();
  }

  /** 会話の実行で参照する実行予定の管理を返す。 */
  @Override
  public reactor.core.scheduler.Scheduler getScheduler() {
    return delegate.getScheduler();
  }

  /** 会話の実行で用いるadvise逐次データを返す。 */
  @Override
  public reactor.core.publisher.Flux<ChatClientResponse> adviseStream(
      ChatClientRequest request, StreamAdvisorChain chain) {
    if (delegate instanceof BaseChatMemoryAdvisor) {
      // Memory advisors save the aggregate on completion, not the finish-reason delta.
      // That final delta can have null text even after a complete answer was streamed.
      var responses =
          reactor.core.publisher.Mono.just(capture(request))
              .publishOn(getScheduler())
              .map(captured -> before(captured, chain))
              .flatMapMany(chain::nextStream);
      return new ChatClientMessageAggregator()
          .aggregateChatClientResponse(responses, response -> after(response, chain));
    }
    return BaseAdvisor.super.adviseStream(capture(request), chain);
  }

  /** 会話の実行で用いるadvise呼び出しを返す。 */
  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    return BaseAdvisor.super.adviseCall(capture(request), chain);
  }

  /** 後から状況を確認できるよう、実行対象を限定した対話の補助を記録する。 */
  private ChatClientRequest capture(ChatClientRequest request) {
    var current =
        (AgentRunContext)
            request
                .context()
                .getOrDefault(AgentRunContext.class.getName(), AgentRunScope.current());
    if (current == null) {
      if (!(delegate instanceof BaseChatMemoryAdvisor)
          || request
              .context()
              .containsKey(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID))
        return request;
      // Preserve the former PromptChatMemoryAdvisor fallback for legacy non-run callers.
      var defaults = new java.util.HashMap<>(request.context());
      defaults.put(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, "default");
      return request.mutate().context(defaults).build();
    }
    var context = new java.util.HashMap<>(request.context());
    context.put(AgentRunContext.class.getName(), current);
    String memoryKey = org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;
    if (!context.containsKey(memoryKey)) context.put(memoryKey, current.conversationId());
    else if (current.projectId() != null
        && context.get(memoryKey) instanceof String id
        && !id.startsWith("project:"))
      context.put(memoryKey, "project:" + current.projectId() + ":" + id);
    var prompt = request.prompt();
    if (prompt.getOptions()
        instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options) {
      var copy = options;
      var tools = new java.util.HashMap<String, Object>();
      if (copy.getToolContext() != null) tools.putAll(copy.getToolContext());
      tools.put(AgentRunContext.class.getName(), current);
      String cancellationKey =
          dev.mikoto2000.rei.core.service.CommandCancellationService.class.getName();
      if (tools.containsKey(cancellationKey))
        context.put(cancellationKey, tools.get(cancellationKey));
      copy = options.mutate().toolContext(tools).build();
      prompt = new org.springframework.ai.chat.prompt.Prompt(prompt.getInstructions(), copy);
    }
    return request.mutate().context(context).prompt(prompt).build();
  }

  /** 会話の実行の本処理へ渡す文脈を準備する。 */
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    try (var scope =
            AgentRunScope.open(
                (AgentRunContext) request.context().get(AgentRunContext.class.getName()));
        var lease = RunCancellation.enterChild(request.prompt())) {
      RunCancellation.checkActive(request.prompt());
      var result = delegate.before(request, chain);
      RunCancellation.checkActive(result.prompt());
      return result;
    }
  }

  /** 会話の実行の実行結果を受け取り、後続の処理へ反映する。 */
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    try (var scope =
            AgentRunScope.open(
                (AgentRunContext) response.context().get(AgentRunContext.class.getName()));
        var lease = RunCancellation.enterChild(response.context())) {
      return delegate.after(response, chain);
    }
  }

  /** 会話の実行で用いる共通形式への調整を返す。 */
  public static java.util.List<Advisor> wrap(java.util.List<Advisor> advisors) {
    return advisors.stream()
        .map(a -> a instanceof BaseAdvisor b ? (Advisor) new RunScopedAdvisor(b) : a)
        .toList();
  }
}
