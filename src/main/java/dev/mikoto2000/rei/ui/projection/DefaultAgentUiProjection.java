package dev.mikoto2000.rei.ui.projection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentRunCompletedPayload;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import dev.mikoto2000.rei.event.AgentRunStartedPayload;
import dev.mikoto2000.rei.event.MessageCompletedPayload;
import dev.mikoto2000.rei.event.MessageDeltaPayload;
import dev.mikoto2000.rei.event.MessageStartedPayload;
import dev.mikoto2000.rei.event.ToolCompletedPayload;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import dev.mikoto2000.rei.event.ToolStartedPayload;

/** Agent Event を UI framework 非依存の現在状態へ変換する。 */
public final class DefaultAgentUiProjection implements AgentUiProjection {

  private static final Logger log = LoggerFactory.getLogger(DefaultAgentUiProjection.class);

  private AgentRunView run = AgentRunView.idle();
  private final Map<String, MessageView> messages = new LinkedHashMap<>();
  private final Map<String, ToolExecutionView> tools = new LinkedHashMap<>();
  private long lastSequence;

  @Override
  public synchronized void apply(AgentEvent event) {
    if (event == null) {
      return;
    }
    if (event.sequence() > 0 && event.sequence() <= lastSequence) {
      log.debug("Ignoring stale Agent Event: sequence={}, lastSequence={}", event.sequence(), lastSequence);
      return;
    }

    try {
      switch (event.type()) {
        case AGENT_RUN_STARTED -> applyRunStarted(event, (AgentRunStartedPayload) event.payload());
        case AGENT_RUN_COMPLETED -> applyRunCompleted(event, (AgentRunCompletedPayload) event.payload());
        case AGENT_RUN_FAILED -> applyRunFailed(event, (AgentRunFailedPayload) event.payload());
        case MESSAGE_STARTED -> applyMessageStarted(event, (MessageStartedPayload) event.payload());
        case MESSAGE_DELTA -> applyMessageDelta(event, (MessageDeltaPayload) event.payload());
        case MESSAGE_COMPLETED -> applyMessageCompleted(event, (MessageCompletedPayload) event.payload());
        case TOOL_STARTED -> applyToolStarted(event, (ToolStartedPayload) event.payload());
        case TOOL_COMPLETED -> applyToolCompleted(event, (ToolCompletedPayload) event.payload());
        case TOOL_FAILED -> applyToolFailed(event, (ToolFailedPayload) event.payload());
        default -> {
          // v1 では Task / Working Set / Context / File を Projection しない。
        }
      }
      lastSequence = Math.max(lastSequence, event.sequence());
    } catch (RuntimeException exception) {
      log.warn("Agent UI Projection ignored malformed event: type={}, id={}", event.type(), event.id(), exception);
    }
  }

  @Override
  public synchronized AgentUiState currentState() {
    return new AgentUiState(run, new ArrayList<>(messages.values()), new ArrayList<>(tools.values()), lastSequence);
  }

  private void applyRunStarted(AgentEvent event, AgentRunStartedPayload payload) {
    messages.clear();
    tools.clear();
    run = new AgentRunView(payload.runId(), AgentRunStatus.RUNNING, event.timestamp(), null, null, null);
  }

  private void applyRunCompleted(AgentEvent event, AgentRunCompletedPayload payload) {
    run = new AgentRunView(payload.runId(), AgentRunStatus.COMPLETED, run.startedAt(), event.timestamp(),
        payload.duration(), null);
  }

  private void applyRunFailed(AgentEvent event, AgentRunFailedPayload payload) {
    run = new AgentRunView(payload.runId(), AgentRunStatus.FAILED, run.startedAt(), event.timestamp(), null,
        ErrorView.from(payload.error()));
  }

  private void applyMessageStarted(AgentEvent event, MessageStartedPayload payload) {
    messages.putIfAbsent(payload.messageId(), new MessageView(payload.messageId(), payload.role(),
        MessageStatus.STREAMING, "", event.timestamp(), null));
  }

  private void applyMessageDelta(AgentEvent event, MessageDeltaPayload payload) {
    MessageView current = messages.get(payload.messageId());
    if (current == null) {
      current = new MessageView(payload.messageId(), null, MessageStatus.STREAMING, "", event.timestamp(), null);
    }
    String delta = payload.delta() == null ? "" : payload.delta();
    messages.put(payload.messageId(), new MessageView(current.messageId(), current.role(), MessageStatus.STREAMING,
        current.text() + delta, current.startedAt(), null));
  }

  private void applyMessageCompleted(AgentEvent event, MessageCompletedPayload payload) {
    MessageView current = messages.get(payload.messageId());
    if (current == null) {
      current = new MessageView(payload.messageId(), payload.role(), MessageStatus.STREAMING, "", null, null);
    }
    String text = payload.text() == null ? current.text() : payload.text();
    String role = payload.role() == null ? current.role() : payload.role();
    messages.put(payload.messageId(), new MessageView(current.messageId(), role, MessageStatus.COMPLETED, text,
        current.startedAt(), event.timestamp()));
  }

  private void applyToolStarted(AgentEvent event, ToolStartedPayload payload) {
    String id = toolId(event, payload.toolCallId());
    tools.putIfAbsent(id, new ToolExecutionView(id, payload.toolName(), ToolExecutionStatus.RUNNING,
        payload.argumentsSummary(), null, null, null, event.timestamp(), null));
  }

  private void applyToolCompleted(AgentEvent event, ToolCompletedPayload payload) {
    String id = toolId(event, payload.toolCallId());
    ToolExecutionView current = tools.get(id);
    if (current == null) {
      current = new ToolExecutionView(id, payload.toolName(), ToolExecutionStatus.RUNNING, null, null, null, null,
          null, null);
    }
    tools.put(id, new ToolExecutionView(id, valueOr(payload.toolName(), current.toolName()),
        ToolExecutionStatus.COMPLETED, current.argumentsSummary(), payload.resultSummary(), null, payload.duration(),
        current.startedAt(), event.timestamp()));
  }

  private void applyToolFailed(AgentEvent event, ToolFailedPayload payload) {
    String id = toolId(event, payload.toolCallId());
    ToolExecutionView current = tools.get(id);
    if (current == null) {
      current = new ToolExecutionView(id, payload.toolName(), ToolExecutionStatus.RUNNING, null, null, null, null,
          null, null);
    }
    tools.put(id, new ToolExecutionView(id, valueOr(payload.toolName(), current.toolName()), ToolExecutionStatus.FAILED,
        current.argumentsSummary(), null, ErrorView.from(payload.error()), null, current.startedAt(), event.timestamp()));
  }

  private String toolId(AgentEvent event, String toolCallId) {
    if (event.correlationId() != null && !event.correlationId().isBlank()) {
      return event.correlationId();
    }
    return toolCallId == null || toolCallId.isBlank() ? event.id() : toolCallId;
  }

  private String valueOr(String preferred, String fallback) {
    return preferred == null ? fallback : preferred;
  }
}
