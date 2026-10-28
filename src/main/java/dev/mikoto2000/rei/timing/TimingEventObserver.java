package dev.mikoto2000.rei.timing;

import static dev.mikoto2000.rei.timing.TimingRecorder.Category;
import static dev.mikoto2000.rei.timing.TimingRecorder.Status;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.SubAgentLifecyclePayload;
import dev.mikoto2000.rei.event.ToolCompletedPayload;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import dev.mikoto2000.rei.event.ToolStartedPayload;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 時間計測イベントの状況を観測し、記録や判断に必要な情報を提供する。
 *
 * <p>Consumes only IDs and fixed tool classifications; no event payload is retained.
 */
@Component
public class TimingEventObserver implements AutoCloseable {
  /** 検索。 */
  private static final Set<String> SEARCH =
      Set.of(
          "webSearch",
          "webSearchAndRead",
          "searchKnowledge",
          "grepMultiQuery",
          "searchAndRead",
          "searchFilePage",
          "repositoryMap",
          "findFile",
          "listFile",
          "searchWorkingSet",
          "searchHistory",
          "search");

  /** 実行時間を記録する窓口。 */
  private final TimingRecorder recorder;

  /** 購読。 */
  private final AgentEventBus.Subscription subscription;

  /** 指定された値や利用先を使って、TimingEventObserverを初期化する。 */
  public TimingEventObserver(TimingRecorder recorder, AgentEventBus bus) {
    this.recorder = recorder;
    subscription = bus.subscribe(this::observe);
  }

  /** 状況を判断する根拠を得るため、時間計測イベント観測を観測する。 */
  private void observe(AgentEvent event) {
    try {
      if (!recorder.enabled() || event.runId() == null) return;
      if (event.payload() instanceof SubAgentLifecyclePayload child
          && event.runId().equals(child.subAgentRunId())) {
        if (event.type() == AgentEventType.SUBAGENT_STARTED)
          recorder.beginRun(event.runId(), event.projectId(), event.sessionId());
        else if (event.type() == AgentEventType.SUBAGENT_COMPLETED)
          recorder.finishRun(event.runId(), Status.SUCCESS);
        else if (event.type() == AgentEventType.SUBAGENT_FAILED)
          recorder.finishRun(
              event.runId(),
              switch (child.status()) {
                case "CANCELLED" -> Status.CANCELLED;
                case "TIMEOUT" -> Status.TIMED_OUT;
                default -> Status.FAILED;
              });
      }
      if (event.type() == AgentEventType.TOOL_STARTED
          && event.payload() instanceof ToolStartedPayload tool) {
        if (recorder.beginSpan(
                event.runId(), tool.toolCallId(), event.runId(), null, null, Category.TOOL)
            && SEARCH.contains(tool.toolName()))
          recorder.beginSpan(
              event.runId(),
              "search." + tool.toolCallId(),
              tool.toolCallId(),
              null,
              null,
              Category.SEARCH);
      } else if (event.type() == AgentEventType.TOOL_COMPLETED
          && event.payload() instanceof ToolCompletedPayload tool) {
        recorder.endSpan(event.runId(), tool.toolCallId(), Status.SUCCESS);
        recorder.endSpan(event.runId(), "search." + tool.toolCallId(), Status.SUCCESS);
      } else if (event.type() == AgentEventType.TOOL_FAILED
          && event.payload() instanceof ToolFailedPayload tool) {
        recorder.endSpan(event.runId(), tool.toolCallId(), Status.FAILED);
        recorder.endSpan(event.runId(), "search." + tool.toolCallId(), Status.FAILED);
      }
    } catch (RuntimeException unavailable) {
      /* Optional observer must not expose event contents in errors. */
    }
  }

  /** 実行時間の把握で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    subscription.unsubscribe();
  }
}
