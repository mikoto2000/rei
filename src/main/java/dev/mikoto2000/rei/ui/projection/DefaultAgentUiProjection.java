package dev.mikoto2000.rei.ui.projection;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Agent Event を UI framework 非依存の現在状態へ変換する。 */
public final class DefaultAgentUiProjection implements AgentUiProjection {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(DefaultAgentUiProjection.class);

  /** 実行。 */
  private AgentRunView run = AgentRunView.idle();

  /** メッセージ一覧。 */
  private final Map<String, MessageView> messages = new LinkedHashMap<>();

  /** ツール一覧。 */
  private final Map<String, ToolExecutionView> tools = new LinkedHashMap<>();

  /** 直近発生順序。 */
  private long lastSequence;

  /** 指定された内容を、現在の対象へ反映する。 */
  @Override
  public synchronized void apply(AgentEvent event) {
    if (event == null) {
      return;
    }
    // A child has its own ephemeral conversation; it must not replace the visible parent's state.
    if (event.sessionId() != null && event.sessionId().startsWith("subagent:")) {
      lastSequence = Math.max(lastSequence, event.sequence());
      return;
    }
    if (event.sequence() > 0 && event.sequence() <= lastSequence) {
      log.debug(
          "Ignoring stale Agent Event: sequence={}, lastSequence={}",
          event.sequence(),
          lastSequence);
      return;
    }

    try {
      switch (event.type()) {
        case AGENT_RUN_STARTED -> applyRunStarted(event, (AgentRunStartedPayload) event.payload());
        case AGENT_RUN_COMPLETED ->
            applyRunCompleted(event, (AgentRunCompletedPayload) event.payload());
        case AGENT_RUN_FAILED -> applyRunFailed(event, (AgentRunFailedPayload) event.payload());
        case MESSAGE_STARTED -> applyMessageStarted(event, (MessageStartedPayload) event.payload());
        case MESSAGE_DELTA -> applyMessageDelta(event, (MessageDeltaPayload) event.payload());
        case MESSAGE_COMPLETED ->
            applyMessageCompleted(event, (MessageCompletedPayload) event.payload());
        case TOOL_STARTED -> applyToolStarted(event, (ToolStartedPayload) event.payload());
        case TOOL_COMPLETED -> applyToolCompleted(event, (ToolCompletedPayload) event.payload());
        case TOOL_FAILED -> applyToolFailed(event, (ToolFailedPayload) event.payload());
        default -> {
          // v1 では Task / Working Set / Context / File を Projection しない。
        }
      }
      lastSequence = Math.max(lastSequence, event.sequence());
    } catch (RuntimeException exception) {
      log.warn(
          "Agent UI Projection ignored malformed event: type={}, id={}",
          event.type(),
          event.id(),
          exception);
    }
  }

  /** 実行状況の表示用整理で用いる現在状態を返す。 */
  @Override
  public synchronized AgentUiState currentState() {
    return new AgentUiState(
        run, new ArrayList<>(messages.values()), new ArrayList<>(tools.values()), lastSequence);
  }

  /** 指定された変更を有効にするため、実行開始を反映する。 */
  private void applyRunStarted(AgentEvent event, AgentRunStartedPayload payload) {
    messages.clear();
    tools.clear();
    run =
        new AgentRunView(
            payload.runId(), AgentRunStatus.RUNNING, event.timestamp(), null, null, null);
  }

  /** 指定された変更を有効にするため、実行完了を反映する。 */
  private void applyRunCompleted(AgentEvent event, AgentRunCompletedPayload payload) {
    run =
        new AgentRunView(
            payload.runId(),
            AgentRunStatus.COMPLETED,
            run.startedAt(),
            event.timestamp(),
            payload.duration(),
            null);
  }

  /** 指定された変更を有効にするため、実行失敗を反映する。 */
  private void applyRunFailed(AgentEvent event, AgentRunFailedPayload payload) {
    var status =
        payload.error() != null && "cancelled".equals(payload.error().code())
            ? AgentRunStatus.CANCELLED
            : AgentRunStatus.FAILED;
    run =
        new AgentRunView(
            payload.runId(),
            status,
            run.startedAt(),
            event.timestamp(),
            null,
            ErrorView.from(payload.error()));
  }

  /** 指定された変更を有効にするため、メッセージ開始を反映する。 */
  private void applyMessageStarted(AgentEvent event, MessageStartedPayload payload) {
    messages.putIfAbsent(
        payload.messageId(),
        new MessageView(
            payload.messageId(),
            payload.role(),
            MessageStatus.STREAMING,
            "",
            event.timestamp(),
            null));
  }

  /** 指定された変更を有効にするため、メッセージ差分を反映する。 */
  private void applyMessageDelta(AgentEvent event, MessageDeltaPayload payload) {
    MessageView current = messages.get(payload.messageId());
    if (current == null) {
      current =
          new MessageView(
              payload.messageId(), null, MessageStatus.STREAMING, "", event.timestamp(), null);
    }
    String delta = payload.delta() == null ? "" : payload.delta();
    messages.put(
        payload.messageId(),
        new MessageView(
            current.messageId(),
            current.role(),
            MessageStatus.STREAMING,
            current.text() + delta,
            current.startedAt(),
            null));
  }

  /** 指定された変更を有効にするため、メッセージ完了を反映する。 */
  private void applyMessageCompleted(AgentEvent event, MessageCompletedPayload payload) {
    MessageView current = messages.get(payload.messageId());
    if (current == null) {
      current =
          new MessageView(
              payload.messageId(), payload.role(), MessageStatus.STREAMING, "", null, null);
    }
    String text = payload.text() == null ? current.text() : payload.text();
    String role = payload.role() == null ? current.role() : payload.role();
    messages.put(
        payload.messageId(),
        new MessageView(
            current.messageId(),
            role,
            MessageStatus.COMPLETED,
            text,
            current.startedAt(),
            event.timestamp()));
  }

  /** 指定された変更を有効にするため、ツール開始を反映する。 */
  private void applyToolStarted(AgentEvent event, ToolStartedPayload payload) {
    String id = toolId(event, payload.toolCallId());
    tools.putIfAbsent(
        id,
        new ToolExecutionView(
            id,
            payload.toolName(),
            ToolExecutionStatus.RUNNING,
            payload.argumentsSummary(),
            null,
            null,
            null,
            event.timestamp(),
            null));
  }

  /** 指定された変更を有効にするため、ツール完了を反映する。 */
  private void applyToolCompleted(AgentEvent event, ToolCompletedPayload payload) {
    String id = toolId(event, payload.toolCallId());
    ToolExecutionView current = tools.get(id);
    if (current == null) {
      current =
          new ToolExecutionView(
              id,
              payload.toolName(),
              ToolExecutionStatus.RUNNING,
              null,
              null,
              null,
              null,
              null,
              null);
    }
    tools.put(
        id,
        new ToolExecutionView(
            id,
            valueOr(payload.toolName(), current.toolName()),
            ToolExecutionStatus.COMPLETED,
            current.argumentsSummary(),
            payload.resultSummary(),
            null,
            payload.duration(),
            current.startedAt(),
            event.timestamp()));
  }

  /** 指定された変更を有効にするため、ツール失敗を反映する。 */
  private void applyToolFailed(AgentEvent event, ToolFailedPayload payload) {
    String id = toolId(event, payload.toolCallId());
    ToolExecutionView current = tools.get(id);
    if (current == null) {
      current =
          new ToolExecutionView(
              id,
              payload.toolName(),
              ToolExecutionStatus.RUNNING,
              null,
              null,
              null,
              null,
              null,
              null);
    }
    tools.put(
        id,
        new ToolExecutionView(
            id,
            valueOr(payload.toolName(), current.toolName()),
            ToolExecutionStatus.FAILED,
            current.argumentsSummary(),
            null,
            ErrorView.from(payload.error()),
            null,
            current.startedAt(),
            event.timestamp()));
  }

  /** 実行状況の表示用整理で用いるツール識別子を返す。 */
  private String toolId(AgentEvent event, String toolCallId) {
    if (event.correlationId() != null && !event.correlationId().isBlank()) {
      return event.correlationId();
    }
    return toolCallId == null || toolCallId.isBlank() ? event.id() : toolCallId;
  }

  /** 実行状況の表示用整理で用いる値またはを返す。 */
  private String valueOr(String preferred, String fallback) {
    return preferred == null ? fallback : preferred;
  }
}
