package dev.mikoto2000.rei.core.chat;

import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;

/** Restores ownership on the advisor's scheduler without changing existing advisor implementations. */
public final class RunScopedAdvisor implements BaseAdvisor {
  private final BaseAdvisor delegate;
  public RunScopedAdvisor(BaseAdvisor delegate) { this.delegate = delegate; }
  public BaseAdvisor delegate() { return delegate; }
  public int getOrder() { return delegate.getOrder(); }
  public String getName() { return delegate.getName(); }
  @Override public reactor.core.scheduler.Scheduler getScheduler() { return delegate.getScheduler(); }
  @Override public reactor.core.publisher.Flux<ChatClientResponse> adviseStream(ChatClientRequest request,
      StreamAdvisorChain chain) {
    if (delegate instanceof BaseChatMemoryAdvisor) {
      // Memory advisors save the aggregate on completion, not the finish-reason delta.
      // That final delta can have null text even after a complete answer was streamed.
      var responses = reactor.core.publisher.Mono.just(capture(request))
          .publishOn(getScheduler())
          .map(captured -> before(captured, chain))
          .flatMapMany(chain::nextStream);
      return new ChatClientMessageAggregator().aggregateChatClientResponse(responses,
          response -> after(response, chain));
    }
    return BaseAdvisor.super.adviseStream(capture(request), chain);
  }
  @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    return BaseAdvisor.super.adviseCall(capture(request), chain);
  }
  private ChatClientRequest capture(ChatClientRequest request) {
    var current = (AgentRunContext) request.context().getOrDefault(AgentRunContext.class.getName(), AgentRunScope.current());
    if (current == null) return request;
    var context = new java.util.HashMap<>(request.context());
    context.put(AgentRunContext.class.getName(), current);
    String memoryKey = org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;
    if (!context.containsKey(memoryKey)) context.put(memoryKey, current.conversationId());
    else if (current.projectId() != null && context.get(memoryKey) instanceof String id && !id.startsWith("project:"))
      context.put(memoryKey, "project:" + current.projectId() + ":" + id);
    var prompt = request.prompt();
    if (prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options) {
      var copy = (org.springframework.ai.model.tool.ToolCallingChatOptions) options.copy();
      var tools = new java.util.HashMap<String, Object>();
      if (copy.getToolContext() != null) tools.putAll(copy.getToolContext());
      tools.put(AgentRunContext.class.getName(), current);
      copy.setToolContext(tools);
      prompt = new org.springframework.ai.chat.prompt.Prompt(prompt.getInstructions(), copy);
    }
    return request.mutate().context(context).prompt(prompt).build();
  }
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    try (var scope = AgentRunScope.open((AgentRunContext) request.context().get(AgentRunContext.class.getName()))) {
      RunCancellation.checkActive(request.prompt());
      var result = delegate.before(request, chain);
      RunCancellation.checkActive(result.prompt());
      return result;
    }
  }
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    try (var scope = AgentRunScope.open((AgentRunContext) response.context().get(AgentRunContext.class.getName()))) {
      return delegate.after(response, chain);
    }
  }
  public static java.util.List<Advisor> wrap(java.util.List<Advisor> advisors) {
    return advisors.stream().map(a -> a instanceof BaseAdvisor b ? (Advisor) new RunScopedAdvisor(b) : a).toList();
  }
}
