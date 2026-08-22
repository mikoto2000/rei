package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;

class ToolEventConfigurationTest {

  private final AgentEventFactory factory = new AgentEventFactory(Clock.systemDefaultZone());
  private final AgentEventPublisher publisher = new InMemoryAgentEventBus();

  @Test
  void createsWrappedProviderWhenMcpProviderAvailable() {
    SyncMcpToolCallbackProvider mcpProvider = mock(SyncMcpToolCallbackProvider.class);
    ToolEventConfiguration config = new ToolEventConfiguration();
    ToolEventCallbackProvider result =
        config.toolEventCallbackProvider(mcpProvider, factory, publisher);

    assertNotNull(result);
  }

}
