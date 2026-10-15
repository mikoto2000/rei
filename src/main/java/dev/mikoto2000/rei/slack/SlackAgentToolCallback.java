package dev.mikoto2000.rei.slack;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Trusted registration authorizes the resolved destination inside askExternalAgent, exactly once.
 */
public final class SlackAgentToolCallback implements ToolCallback {
  private final ToolCallback delegate;

  SlackAgentToolCallback(ToolCallback delegate) {
    this.delegate = delegate;
  }

  public boolean authorizesSpecification() {
    return "askExternalAgent".equals(getToolDefinition().name());
  }

  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  public String call(String input) {
    return delegate.call(input);
  }

  public String call(String input, ToolContext context) {
    return delegate.call(input, context);
  }
}
