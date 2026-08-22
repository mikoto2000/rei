package dev.mikoto2000.rei.event;

import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP の {@link SyncMcpToolCallbackProvider} を {@link ToolEventCallbackProvider} でラップする設定。
 *
 * <p>MCP Provider が存在する場合のみ、各 ToolCallback を {@link ToolEventCallbackDecorator} で
 * 包んだ Provider を Bean として提供する。MCP Provider がない場合は Bean 自体を生成しない。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ToolEventConfiguration {

  @Bean
  @ConditionalOnBean(SyncMcpToolCallbackProvider.class)
  public ToolEventCallbackProvider toolEventCallbackProvider(
      SyncMcpToolCallbackProvider mcpToolCallbackProvider,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    return new ToolEventCallbackProvider(mcpToolCallbackProvider, eventFactory, eventPublisher);
  }
}
