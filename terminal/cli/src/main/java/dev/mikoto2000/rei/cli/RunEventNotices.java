package dev.mikoto2000.rei.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.function.Consumer;

/** Shell の補助通知表示。回答本文のストリーム状態は RunEventOutput が所有する。 */
final class RunEventNotices {
  private final Consumer<String> output;
  private Instant lastTopicSummary;

  RunEventNotices(Consumer<String> output) {
    this.output = output;
  }

  void accept(String type, JsonNode p, Instant timestamp, String project) {
    switch (type) {
      case "memory.auto-sleep.stopped" -> line(p.path("status").asText());
      case "dependency.failed", "dependency.cancelled" ->
          line(
              "[dependency] "
                  + text(p, "dependencyId")
                  + " "
                  + text(p, "state")
                  + " 理由: "
                  + reason(p.path("reason")));
      case "memory.sleep.failed" ->
          line("[memory.sleep] " + text(p, "status") + " (" + text(p, "sleepRunId") + ")");
      case "task.failed" ->
          line(
              "[task] failed "
                  + text(p, "taskId")
                  + ": "
                  + RunEventOutput.errorReason(p.path("error")));
      case "goal.updated" -> {
        line(
            "[goal] "
                + text(p, "goalId")
                + " "
                + text(p, "status")
                + " runs="
                + number(p, "attempts")
                + "/"
                + number(p, "maxRuns")
                + " llm="
                + number(p, "llmCallsUsed")
                + "/"
                + number(p, "maxLlmCalls"));
        if (!p.path("reason").asText("").isBlank()) line("[goal] 理由: " + reason(p.path("reason")));
      }
      case "work_context.updated", "work_context.update.failed" ->
          line("[" + type + "] " + text(p, "status") + " revision=" + text(p, "revision"));
      case "application.shutdown.started" -> line("[application] グレースフルシャットダウンを開始します。");
      case "computer_use.progress" ->
          line(
              "[computer_use] step="
                  + number(p, "step")
                  + " "
                  + text(p, "phase")
                  + optional(p, "action", " ")
                  + (p.hasNonNull("target")
                      ? " "
                          + text(p, "target")
                          + " ("
                          + text(p, "x")
                          + ","
                          + text(p, "y")
                          + ") confidence="
                          + text(p, "confidence")
                      : "")
                  + optional(p, "reason", " "));
      case "execution.started",
          "execution.completed",
          "execution.failed",
          "execution.cancelled" -> {
        String status = text(p, "status");
        String state =
            switch (status) {
              case "RUNNING" -> "started";
              case "COMPLETED" -> "completed";
              case "CANCELLED" -> "cancelled";
              default -> "failed";
            };
        line(
            "["
                + text(p, "executionType").toLowerCase(java.util.Locale.ROOT)
                + "."
                + state
                + "] "
                + project
                + " ("
                + text(p, "executionId")
                + ")");
        if (!p.path("request").asText("").isBlank())
          line("Source / request: " + text(p, "request"));
        if (status.equals("TIMED_OUT")) line("Status: TIMED_OUT");
        if (!p.path("output").asText("").isBlank()) line(text(p, "output"));
      }
      case "history.search.completed" ->
          line(
              "[history.search] scope="
                  + text(p, "scope")
                  + " currentHits="
                  + number(p, "currentProjectHitCount")
                  + " crossProjectHits="
                  + number(p, "crossProjectHitCount")
                  + " projectsSearched="
                  + number(p, "searchedProjectCount"));
      case "user.intervention.received" -> line("[user] guidance queued");
      case "user.intervention.applied" -> line("[user] guidance applied");
      case "progress.detected",
          "stagnation.updated",
          "stagnation.detected",
          "stagnation.replan_requested",
          "stagnation.recovered",
          "stagnation.stopped" -> {
        String reason = p.path("reason").asText("");
        line(
            switch (type) {
              case "progress.detected" ->
                  p.hasNonNull("evidence")
                      ? "[progress] "
                          + text(p.path("evidence"), "kind")
                          + ": "
                          + text(p.path("evidence"), "description")
                          + " ("
                          + text(p.path("evidence"), "source")
                          + ")"
                      : "[progress] meaningful progress detected";
              case "stagnation.updated" ->
                  reason.equals("waiting_for_dependency")
                      ? "[waiting] awaiting dependency"
                      : reason.equals("INFORMATION_UNKNOWN")
                          ? "[stagnation] progress unknown: reason=INFORMATION_UNKNOWN ledgerStatus=UNKNOWN"
                          : "[stagnation] no progress: "
                              + number(p, "consecutiveNoProgressIterations")
                              + "/"
                              + number(p, "threshold");
              case "stagnation.detected" -> "[stagnation] threshold reached";
              case "stagnation.replan_requested" ->
                  "[stagnation] replanning ("
                      + number(p, "stagnationReplanCount")
                      + "/"
                      + number(p, "maxStagnationReplans")
                      + ")";
              case "stagnation.recovered" -> "[stagnation] recovered: meaningful progress detected";
              default -> "[stagnation] repeated after replanning -> stopped (STAGNATED)";
            });
        if (Set.of(
                "REPEATED_READ",
                "REPEATED_SEARCH_OR_ACTION",
                "TOOL_FAILURE",
                "WORK_STALLED",
                "DUPLICATE_OR_UNSUPPORTED_INFORMATION")
            .contains(reason)) line("[stagnation] reason=" + reason + " ledgerStatus=HEALTHY");
      }
      case "memory.consolidation.suggested" ->
          line("[memory] 記憶整理を実行することをお勧めします。/memory consolidate を実行してください。");
      case "working_set.item.added" ->
          line(
              "[working-set] + "
                  + displayName(p.path("identifier").asText(null), p.path("path").asText(null))
                  + reasonSuffix(p));
      case "working_set.item.removed" ->
          line(
              "[working-set] - "
                  + displayName(null, p.path("itemId").asText(null))
                  + reasonSuffix(p));
      case "working_set.search.started" ->
          line(
              "[working-set] → search \""
                  + bounded(p.path("query").asText(""), 120).replace('"', '\'')
                  + "\"");
      case "working_set.search.completed" ->
          line(
              "[working-set] ✓ "
                  + number(p, "hitCount")
                  + " hits → "
                  + number(p, "candidateCount")
                  + " candidates → "
                  + number(p, "selectedCount")
                  + " selected"
                  + (p.path("alreadyPresentCount").asInt() == 0
                      ? ""
                      : ", " + number(p, "alreadyPresentCount") + " already present")
                  + " ("
                  + number(p, "durationMs")
                  + " ms)");
      case "working_set.context.injected" ->
          line(
              "[working-set] injected "
                  + number(p, "itemCount")
                  + " items into context ("
                  + number(p, "contextCharacters")
                  + " chars)");
      case "context.injected" ->
          line(
              "[context] injected "
                  + p.path("source").asText("").toLowerCase(java.util.Locale.ROOT)
                  + (p.hasNonNull("itemCount") ? ", " + number(p, "itemCount") + " items" : "")
                  + " ("
                  + number(p, "contextCharacters")
                  + " chars)");
      case "context.budget.evaluated" ->
          line(
              "[context] budget evaluated: "
                  + number(p, "totalTokens")
                  + "/"
                  + number(p, "inputBudget")
                  + " tokens, included "
                  + p.path("included").size()
                  + ", dropped "
                  + p.path("dropped").size());
      case "context.budget.trimmed" -> {
        var dropped = new java.util.ArrayList<String>();
        p.path("dropped").forEach(value -> dropped.add(value.asText()));
        line("[context] budget trimmed: dropped " + String.join(", ", dropped));
      }
      case "file_summary.saved" ->
          line(
              "[file-summary] saved "
                  + displayName(null, p.path("path").asText(null))
                  + " ("
                  + number(p, "summaryCharacters")
                  + " chars)");
      case "file_summary.invalidated" ->
          line("[file-summary] invalidated " + displayName(null, p.path("path").asText(null)));
      case "file_summary.stale_skipped" ->
          line("[file-summary] skipped stale " + displayName(null, p.path("path").asText(null)));
      case "file.created", "file.modified", "file.deleted" ->
          line(
              "[file] "
                  + type.substring(5)
                  + " "
                  + displayName(null, p.path("path").asText(null))
                  + (type.equals("file.modified")
                          && (p.hasNonNull("addedLines") || p.hasNonNull("removedLines"))
                      ? " (+" + number(p, "addedLines") + "/-" + number(p, "removedLines") + ")"
                      : ""));
      case "background_process.started" ->
          line("[process] started " + text(p, "processId") + " pid=" + number(p, "pid"));
      case "background_process.completed", "background_process.killed" ->
          line(
              "[process] "
                  + type.substring("background_process.".length())
                  + " "
                  + text(p, "processId")
                  + " exit="
                  + text(p, "exitCode")
                  + " ("
                  + String.format(
                      java.util.Locale.ROOT, "%.1f", p.path("elapsedSeconds").asDouble())
                  + " s)");
      case "background_process.failed" ->
          line(
              "[process] failed "
                  + text(p, "processId")
                  + ": "
                  + RunEventOutput.errorReason(p.path("error")));
      case "delegation.started",
          "delegation.completed",
          "delegation.failed",
          "delegation.cancelled" ->
          line(
              "[delegation] "
                  + text(p, "agent")
                  + " "
                  + type.substring("delegation.".length())
                  + ": "
                  + text(p, "summary"));
      case "topic.candidates.refreshed" -> {
        if (p.path("candidateCount").asInt() > 0) {
          line("[topic] candidates refreshed: " + number(p, "candidateCount"));
          p.path("topics").forEach(topic -> line("        - " + oneLine(topic.asText())));
        }
      }
      case "topic.spoken", "topic.speak.skipped", "topic.auto_speak.suppressed" -> {
        if (type.equals("topic.speak.skipped") && text(p, "reason").equals("COOLDOWN")) return;
        if (lastTopicSummary != null
            && timestamp.isBefore(lastTopicSummary.plus(Duration.ofMinutes(30)))) return;
        lastTopicSummary = timestamp;
        line(
            switch (type) {
              case "topic.spoken" ->
                  "[topic] spoken: "
                      + p.path("candidateId").asText("none")
                      + " -> "
                      + text(p, "messageId");
              case "topic.speak.skipped" -> "[topic] speak skipped: " + text(p, "reason");
              default -> "[topic] auto speak suppressed: " + oneLine(p.path("reason").asText(""));
            });
      }
      case "topic.generation.failed" ->
          line(
              "[topic] generation failed: "
                  + text(p, "stage")
                  + " "
                  + oneLine(p.path("errorSummary").asText("")));
      // Shell の summary 設定では以下の監査イベントは画面に出さない。
      default -> {}
    }
  }

  private void line(String value) {
    output.accept(value);
  }

  private static String text(JsonNode p, String key) {
    return p.path(key).asText("null");
  }

  private static long number(JsonNode p, String key) {
    return p.path(key).asLong();
  }

  private static String optional(JsonNode p, String key, String prefix) {
    return p.hasNonNull(key) ? prefix + text(p, key) : "";
  }

  private static String reason(JsonNode value) {
    return dev.mikoto2000.rei.event.FailureMessages.text(value.asText(null));
  }

  private static String oneLine(String value) {
    return value.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
  }

  private static String bounded(String value, int length) {
    String safe = oneLine(value);
    return safe.length() <= length ? safe : safe.substring(0, length - 1) + "…";
  }

  private static String reasonSuffix(JsonNode p) {
    String reason = bounded(p.path("reason").asText(""), 120);
    return reason.isBlank() ? "" : " (" + reason + ")";
  }

  private static String displayName(String identifier, String path) {
    String value = identifier == null || identifier.isBlank() ? path : identifier;
    if (value == null || value.isBlank()) return "item";
    String normalized = value.replace('\\', '/');
    return normalized.substring(normalized.lastIndexOf('/') + 1);
  }
}
