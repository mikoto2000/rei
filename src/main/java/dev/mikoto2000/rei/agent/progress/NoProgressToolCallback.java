package dev.mikoto2000.rei.agent.progress;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

public class NoProgressToolCallback implements ToolCallback {

  private static final Logger log = LoggerFactory.getLogger(NoProgressToolCallback.class);

  private final ToolCallback delegate;
  private final AgentProgressSessionRegistry sessionRegistry;
  private final AgentProgressProperties properties;

  public NoProgressToolCallback(
      ToolCallback delegate,
      AgentProgressSessionRegistry sessionRegistry,
      AgentProgressProperties properties) {
    this.delegate = delegate;
    this.sessionRegistry = sessionRegistry;
    this.properties = properties;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  @Override
  public String call(String toolInput) {
    return delegate.call(toolInput);
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    if (!properties.isEnabled()) {
      return delegate.call(toolInput, toolContext);
    }
    try {
      String result = delegate.call(toolInput, toolContext);
      record(toolContext, toolInput, result, false);
      return result;
    } catch (AgentNoProgressException e) {
      throw e;
    } catch (RuntimeException e) {
      record(toolContext, toolInput, e.getMessage(), true);
      throw e;
    }
  }

  private void record(ToolContext toolContext, String toolInput, String result, boolean error) {
    session(toolContext).ifPresent(session -> {
      ProgressTrackerSnapshot snapshot = session.recordToolResult(toolName(), toolInput, result, error);
      ProgressEvaluation evaluation = snapshot.evaluation();
      log.debug("Progress evaluation: progressed={}, level={}, noProgressCount={}/{}, reasons={}",
          evaluation.progressed(),
          evaluation.level(),
          snapshot.noProgressCount(),
          snapshot.maxNoProgressIterations(),
          evaluation.reasons());
      if (snapshot.shouldStop()) {
        log.warn("Agent stopped because no meaningful progress was detected for {} consecutive iterations. reasons={}",
            snapshot.maxNoProgressIterations(), evaluation.reasons());
        throw new AgentNoProgressException(snapshot);
      }
    });
  }

  private java.util.Optional<AgentProgressSession> session(ToolContext toolContext) {
    if (toolContext == null || toolContext.getContext() == null) {
      return java.util.Optional.empty();
    }
    Object value = toolContext.getContext().get(AgentProgressSessionRegistry.TOOL_CONTEXT_SESSION_ID);
    return sessionRegistry.find(value == null ? null : value.toString());
  }

  private String toolName() {
    ToolDefinition definition = delegate.getToolDefinition();
    return definition == null ? "unknown" : definition.name();
  }
}
