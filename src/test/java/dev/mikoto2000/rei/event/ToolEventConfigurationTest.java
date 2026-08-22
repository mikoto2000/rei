package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

class ToolEventConfigurationTest {

  private final AgentEventFactory factory = new AgentEventFactory(Clock.systemDefaultZone());
  private final AgentEventPublisher publisher = new InMemoryAgentEventBus();

  @Test
  void createsWrappedProviderWhenMcpProviderAvailable() {
    ToolCallbackProvider mcpProvider = mock(ToolCallbackProvider.class);
    ObjectProvider<ToolCallbackProvider> mcpProviderProvider = mockProviderReturning(mcpProvider);

    ToolEventConfiguration config = new ToolEventConfiguration();
    ToolEventCallbackProvider result =
        config.toolEventCallbackProvider(mcpProviderProvider, factory, publisher);

    assertNotNull(result);
  }

  @Test
  void returnsNullWhenMcpProviderUnavailable() {
    ObjectProvider<ToolCallbackProvider> mcpProviderProvider = mockProviderReturning(null);

    ToolEventConfiguration config = new ToolEventConfiguration();
    ToolEventCallbackProvider result =
        config.toolEventCallbackProvider(mcpProviderProvider, factory, publisher);

    assertNull(result);
  }

  private <T> ObjectProvider<T> mockProviderReturning(T value) {
    @SuppressWarnings("unchecked")
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}
