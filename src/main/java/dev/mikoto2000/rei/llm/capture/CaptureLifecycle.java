package dev.mikoto2000.rei.llm.capture;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import org.springframework.stereotype.Component;

/**
 * モデル応答の記録に必要な取得記録受付から終了までの管理を扱う。
 *
 * <p>Metadata-only terminal subscription also covers cancellation before execution.
 */
@Component
public final class CaptureLifecycle implements AutoCloseable {
  /** 購読。 */
  private final AgentEventBus.Subscription subscription;

  /** 指定された値や利用先を使って、CaptureLifecycleを初期化する。 */
  public CaptureLifecycle(CaptureStore store, AgentEventBus bus) {
    subscription =
        bus.subscribe(
            event -> {
              if (event.runId() == null) return;
              switch (event.type()) {
                case AGENT_RUN_COMPLETED -> store.finish(event.runId(), "COMPLETED");
                case AGENT_RUN_FAILED ->
                    store.finish(
                        event.runId(),
                        event.payload() instanceof AgentRunFailedPayload failed
                                && failed.error() != null
                                && "cancelled".equals(failed.error().code())
                            ? "CANCELLED"
                            : "FAILED");
                case AGENT_RUN_CANCELLED -> store.finish(event.runId(), "CANCELLED");
                default -> {}
              }
            });
  }

  /** モデル応答の記録で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    subscription.unsubscribe();
  }
}
