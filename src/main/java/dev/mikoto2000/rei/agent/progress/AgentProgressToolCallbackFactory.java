package dev.mikoto2000.rei.agent.progress;

import java.util.Arrays;
import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

@Component
public class AgentProgressToolCallbackFactory {

  private final AgentProgressSessionRegistry sessionRegistry;
  private final AgentProgressProperties properties;

  public AgentProgressToolCallbackFactory(
      AgentProgressSessionRegistry sessionRegistry,
      AgentProgressProperties properties) {
    this.sessionRegistry = sessionRegistry;
    this.properties = properties;
  }

  public ToolCallback[] wrap(ToolCallback[] callbacks) {
    if (callbacks == null) {
      return new ToolCallback[0];
    }
    return Arrays.stream(callbacks)
        .map(callback -> new NoProgressToolCallback(callback, sessionRegistry, properties))
        .toArray(ToolCallback[]::new);
  }

  public List<ToolCallback> wrap(List<ToolCallback> callbacks) {
    return List.of(wrap(callbacks.toArray(ToolCallback[]::new)));
  }
}
