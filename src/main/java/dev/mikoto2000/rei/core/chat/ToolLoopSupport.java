package dev.mikoto2000.rei.core.chat;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;

/** Shared Spring AI tool dispatch boundary for explicit application-owned loops. */
public final class ToolLoopSupport {
  /** Refuse ambient tool authority; isolated requests must explicitly supply their callbacks. */
  public static void requireNoDefaultTools(org.springframework.ai.chat.model.ChatModel model) {
    var options = model.getOptions();
    requireNoRawTools(options);
    if (options instanceof ToolCallingChatOptions tools
        && !org.springframework.util.CollectionUtils.isEmpty(tools.getToolCallbacks())) {
      throw new IllegalArgumentException("SubAgent model must not carry default tools");
    }
  }

  /** SDK extra-body parameters must not bypass Rei's explicit callback/permission boundary. */
  public static void requireNoRawTools(ChatOptions options) {
    if (options instanceof org.springframework.ai.openai.OpenAiChatOptions openAi
        && openAi.getExtraBody() != null) {
      Object rawTools = openAi.getExtraBody().get("tools");
      if (rawTools != null && !(rawTools instanceof java.util.Collection<?> tools && tools.isEmpty())) {
        throw new IllegalArgumentException("Model must not carry raw default tools");
      }
    }
  }

  // Rei already bounds explicit loops with durable run budgets/maxSteps and timeouts.
  // Library history-based limits reset at user interventions and can abort a tool batch
  // before Rei checkpoints it, so retain the existing application-owned limits.
  private final ToolCallingManager manager = ToolCallingManager.builder()
      .unlimitedCallsPerTool().unlimitedTotalToolCalls().build();
  public ToolExecutionResult execute(Prompt prompt, ChatResponse response) {
    return manager.executeToolCalls(prompt, response);
  }
  public ToolExecutionResult executeResumable(Prompt prompt,ChatResponse response,dev.mikoto2000.rei.core.stagnation.RunExecutionContext context) {
    if(!context.hasApprovalContinuation())return execute(prompt,response);
    if(response.getResults().size()!=1)throw new IllegalArgumentException("Approval continuation requires a single selected generation");
    var output=response.getResult().getOutput();
    return executePending(prompt,new RunContinuation.PendingTools(java.util.Objects.toString(output.getText(),""),output.getToolCalls(),java.util.List.of()),context,false);
  }
  public ToolExecutionResult executePending(Prompt prompt,RunContinuation.PendingTools pending,
      dev.mikoto2000.rei.core.stagnation.RunExecutionContext context) {
    return executePending(prompt,pending,context,true);
  }
  private ToolExecutionResult executePending(Prompt prompt,RunContinuation.PendingTools pending,
      dev.mikoto2000.rei.core.stagnation.RunExecutionContext context,boolean replay) {
    var options=(ToolCallingChatOptions)prompt.getOptions();
    var callbacks=new java.util.HashMap<String,org.springframework.ai.tool.ToolCallback>();
    options.getToolCallbacks().forEach(callback->callbacks.put(callback.getToolDefinition().name(),callback));
    var ids=new java.util.HashSet<String>();
    for(var call:pending.calls())if(!callbacks.containsKey(call.name())||!ids.add(call.id()))throw new IllegalArgumentException("Unrequested or duplicate saved Tool");
    var completed=new java.util.ArrayList<>(pending.completed());
    if(completed.size()>pending.calls().size())throw new IllegalArgumentException("Invalid completed Tool prefix");
    for(int i=0;i<completed.size();i++)if(!completed.get(i).id().equals(pending.calls().get(i).id())||!completed.get(i).name().equals(pending.calls().get(i).name()))throw new IllegalArgumentException("Saved Tool prefix mismatch");
    for(int i=completed.size();i<pending.calls().size();i++) {
      var call=pending.calls().get(i);context.checkActive();
      if(replay)context.checkApprovalContinuationExecution();
      try {
        var values=new java.util.HashMap<String,Object>();if(options.getToolContext()!=null)values.putAll(options.getToolContext());
        values.put("toolCallId",call.id());
        String result=callbacks.get(call.name()).call(call.arguments(),new org.springframework.ai.chat.model.ToolContext(values));
        completed.add(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(call.id(),call.name(),result));
      }catch(RuntimeException error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
          if(cause instanceof dev.mikoto2000.rei.core.policy.ToolPermissionException permission&&permission.approvalId()!=null) {
            if(context.suspendForApproval(permission.approvalId(),prompt.getInstructions(),new RunContinuation.PendingTools(pending.text(),pending.calls(),java.util.List.copyOf(completed))))
              throw new RunWaitingException();
          }
          if(cause.getCause()==cause)break;
        }
        throw error;
      }
    }
    var history=new java.util.ArrayList<>(prompt.getInstructions());
    history.add(org.springframework.ai.chat.messages.AssistantMessage.builder().content(pending.text()).toolCalls(pending.calls()).build());
    history.add(org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(completed).build());
    boolean direct=pending.calls().stream().allMatch(call->callbacks.get(call.name()).getToolMetadata().returnDirect());
    return ToolExecutionResult.builder().conversationHistory(history).returnDirect(direct).build();
  }
}
