package dev.mikoto2000.rei.event;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP の {@link ToolCallbackProvider} を {@link ToolEventCallbackProvider} でラップする設定。
 *
 * <p>MCP Provider が存在する場合のみ、各 ToolCallback を {@link ToolEventCallbackDecorator} で
 * 包んだ Provider を Bean として提供する。存在しない場合は null を返し、
 * {@code AiConfiguration} / {@code LlmChatClientProvider} の既存の null チェックに委ねる。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ToolEventConfiguration {

  @Bean
  public ToolEventCallbackProvider toolEventCallbackProvider(
      ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    ToolCallbackProvider mcpProvider = mcpToolCallbackProvider.getIfAvailable();
    if (mcpProvider == null) {
      return null;
    }
    return new ToolEventCallbackProvider(mcpProvider, eventFactory, eventPublisher);
  }
}
