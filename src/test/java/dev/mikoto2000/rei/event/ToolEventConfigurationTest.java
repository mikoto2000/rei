package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;

/** ツールイベント機能の構成の結果と状態の契約を検証するテスト。 */
class ToolEventConfigurationTest {

  /** 検証に使う生成元。 */
  private final AgentEventFactory factory = new AgentEventFactory(Clock.systemDefaultZone());

  /** 検証に使う通知先。 */
  private final AgentEventPublisher publisher = new InMemoryAgentEventBus();

  /** 実行状況の通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void createsWrappedProviderWhenMcpProviderAvailable() {
    SyncMcpToolCallbackProvider mcpProvider = mock(SyncMcpToolCallbackProvider.class);
    ToolEventConfiguration config = new ToolEventConfiguration();
    ToolEventCallbackProvider result =
        config.toolEventCallbackProvider(mcpProvider, factory, publisher);

    assertNotNull(result);
  }
}
