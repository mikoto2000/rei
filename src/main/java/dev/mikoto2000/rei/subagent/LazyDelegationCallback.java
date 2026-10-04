package dev.mikoto2000.rei.subagent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Keeps default ChatClient construction independent of the application runner dependency graph. */
public final class LazyDelegationCallback implements ToolCallback {
  private final ObjectProvider<SubAgentTools> tools;
  private final String name;
  public LazyDelegationCallback(ObjectProvider<SubAgentTools> tools) { this(tools,"delegateTask"); }
  public LazyDelegationCallback(ObjectProvider<SubAgentTools> tools,String name) { this.tools=tools;this.name=name; }
  public ToolDefinition getToolDefinition() { return tools.getObject().callback(name).getToolDefinition(); }
  public String call(String input) { return tools.getObject().callback(name).call(input); }
  public String call(String input, ToolContext context) { return tools.getObject().callback(name).call(input, context); }
}
