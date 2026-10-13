package dev.mikoto2000.rei.subagent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import dev.mikoto2000.rei.core.contextbudget.ResultDelivery;

/** Keeps default ChatClient construction independent of the application runner dependency graph. */
public final class LazyDelegationCallback implements DeliveryToolRegistration.Registered {
  private final ObjectProvider<SubAgentTools> tools;
  private final String name;
  public LazyDelegationCallback(ObjectProvider<SubAgentTools> tools) { this(tools,"delegateTask"); }
  public LazyDelegationCallback(ObjectProvider<SubAgentTools> tools,String name) { this.tools=tools;this.name=name; }
  public ToolDefinition getToolDefinition() { return tools.getObject().callback(name).getToolDefinition(); }
  public ToolMetadata getToolMetadata() { return tools.getObject().callback(name).getToolMetadata(); }
  public ResultDelivery.Kind deliveryKind() { return DeliveryToolRegistration.kind(tools.getObject().callback(name)); }
  public boolean reviewPage() {
    return tools.getObject().callback(name) instanceof DeliveryToolRegistration.Registered registered && registered.reviewPage();
  }
  public String call(String input) { return tools.getObject().callback(name).call(input); }
  public String call(String input, ToolContext context) { return tools.getObject().callback(name).call(input, context); }
}
