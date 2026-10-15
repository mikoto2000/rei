package dev.mikoto2000.rei.timing;

import static dev.mikoto2000.rei.timing.TimingRecorder.*;

import dev.mikoto2000.rei.event.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Consumes only IDs and fixed tool classifications; no event payload is retained. */
@Component
public class TimingEventObserver implements AutoCloseable {
  private static final Set<String> SEARCH =
      Set.of(
          "webSearch",
          "webSearchAndRead",
          "searchKnowledge",
          "grepMultiQuery",
          "searchAndRead",
          "repositoryMap",
          "findFile",
          "listFile",
          "searchWorkingSet",
          "searchHistory",
          "search");
  private final TimingRecorder recorder;
  private final AgentEventBus.Subscription subscription;

  public TimingEventObserver(TimingRecorder recorder, AgentEventBus bus) {
    this.recorder = recorder;
    subscription = bus.subscribe(this::observe);
  }

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

  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    subscription.unsubscribe();
  }
}
