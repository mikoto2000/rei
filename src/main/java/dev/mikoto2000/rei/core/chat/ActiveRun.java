package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.time.Instant;

/** Immutable runtime view. Identity is captured at dispatch, never inferred from Shell selection. */
public record ActiveRun(String runId, String projectId, String conversationId, Path projectRoot,
    String requestSummary, Instant startedAt, Status status) {
  public enum Status { RUNNING }
  static ActiveRun of(AgentRunContext context, String request) {
    return new ActiveRun(context.runId(), context.projectId(), context.conversationId(), context.projectRoot(),
        summary(request), Instant.now(), Status.RUNNING);
  }
  public static String summary(String request) {
    String text = request.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
    return text.codePointCount(0, text.length()) <= 80 ? text : text.substring(0, text.offsetByCodePoints(0, 77)) + "...";
  }
}
