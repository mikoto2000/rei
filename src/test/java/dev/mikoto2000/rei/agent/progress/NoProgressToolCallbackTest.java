package dev.mikoto2000.rei.agent.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class NoProgressToolCallbackTest {

  @Test
  void throwsWhenNoProgressThresholdIsReached() {
    ToolCallback delegate = mock(ToolCallback.class);
    when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder()
        .name("readFile")
        .description("read")
        .inputSchema("{}")
        .build());
    when(delegate.call(eq("{}"), any(ToolContext.class))).thenReturn("same result");

    AgentProgressProperties properties = new AgentProgressProperties();
    properties.setMaxNoProgressIterations(2);
    AgentProgressSessionRegistry registry = new AgentProgressSessionRegistry();
    String sessionId = registry.start("goal", properties.getMaxNoProgressIterations());
    NoProgressToolCallback callback = new NoProgressToolCallback(delegate, registry, properties);
    ToolContext context = context(sessionId);

    callback.call("{}", context);
    callback.call("{}", context);

    AgentNoProgressException exception = assertThrows(AgentNoProgressException.class, () -> callback.call("{}", context));
    assertEquals(2, exception.snapshot().noProgressCount());
    verify(delegate, times(3)).call("{}", context);
  }

  @Test
  void disabledProgressGuardDelegatesWithoutTracking() {
    ToolCallback delegate = mock(ToolCallback.class);
    when(delegate.call(eq("{}"), any(ToolContext.class))).thenReturn("ok");

    AgentProgressProperties properties = new AgentProgressProperties();
    properties.setEnabled(false);
    NoProgressToolCallback callback = new NoProgressToolCallback(delegate, new AgentProgressSessionRegistry(), properties);
    ToolContext context = context("missing");

    assertEquals("ok", callback.call("{}", context));
  }

  private ToolContext context(String sessionId) {
    return new ToolContext(Map.of(AgentProgressSessionRegistry.TOOL_CONTEXT_SESSION_ID, sessionId));
  }
}
