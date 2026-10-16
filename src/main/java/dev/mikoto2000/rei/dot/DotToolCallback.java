package dev.mikoto2000.rei.dot;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Trusted registration authorizes the resolved public snapshot exactly once. */
public final class DotToolCallback implements ToolCallback {
  private final ToolCallback delegate;

  DotToolCallback(ToolCallback delegate) {
    this.delegate = delegate;
  }

  public boolean authorizesSpecification() {
    return getToolDefinition().name().equals("requestDotResearch");
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
