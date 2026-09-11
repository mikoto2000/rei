package dev.mikoto2000.rei.subagent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Keeps default ChatClient construction independent of the application runner dependency graph. */
public final class LazyDelegationCallback implements ToolCallback {
  private final ObjectProvider<SubAgentTools> tools;
  public LazyDelegationCallback(ObjectProvider<SubAgentTools> tools) { this.tools = tools; }
  public ToolDefinition getToolDefinition() { return tools.getObject().callback().getToolDefinition(); }
  public String call(String input) { return tools.getObject().callback().call(input); }
  public String call(String input, ToolContext context) { return tools.getObject().callback().call(input, context); }
}
