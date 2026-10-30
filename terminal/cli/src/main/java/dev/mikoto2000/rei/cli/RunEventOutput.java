package dev.mikoto2000.rei.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 軽量CLIでの対話に必要な実行イベント出力を扱う。
 *
 * <p>Keeps provider chunk boundaries out of terminal lines and preserves the input prompt.
 */
final class RunEventOutput {
  /** 出力。 */
  private final Consumer<String> output;

  /** 承認出力。 */
  private final Consumer<String> approvalOutput;

  /** 逐次通知済みの対象。 */
  private final Set<String> streamed = new HashSet<>();

  /** 無視する対象メッセージ一覧。 */
  private final Set<String> ignoredMessages = new HashSet<>();

  /** 待機中。 */
  private final StringBuilder pending = new StringBuilder();

  /** 活動中。 */
  private String active;

  private final java.util.Map<String, String> llmFeatures = new java.util.HashMap<>();

  private final RunEventNotices shellNotices;

  private String projectId;

  /**
   * 軽量CLIでの対話で受け渡す通知を表す。
   *
   * @param type 種別
   * @param payload 通知内容
   */
  private record Notice(
      String type, JsonNode payload, java.time.Instant timestamp, String project) {}

  /** 案内の回数。 */
  private final List<Notice> notices = new ArrayList<>();

  /** 軽量CLIでの対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  RunEventOutput(Consumer<String> output) {
    this(
        output,
        message -> output.accept(dev.mikoto2000.rei.core.completion.ApprovalNotice.text(message)));
  }

  /** 指定された値や利用先を使って、RunEventOutputを初期化する。 */
  RunEventOutput(Consumer<String> output, Consumer<String> approvalOutput) {
    this.output = output;
    this.approvalOutput = approvalOutput;
    this.shellNotices = new RunEventNotices(output);
  }

  void acceptEvent(String type, JsonNode event) {
    projectId = event.path("projectId").asText(null);
    accept(type, event.path("payload"), java.time.Instant.parse(event.path("timestamp").asText()));
  }

  /** 要求を後続の処理へ引き渡すため、実行イベント出力を受け付ける。 */
  void accept(String type, JsonNode payload) {
    accept(type, payload, java.time.Instant.now());
  }

  /** 要求を後続の処理へ引き渡すため、実行イベント出力を受け付ける。 */
  void accept(String type, JsonNode payload, java.time.Instant timestamp) {
    boolean thinking = type.startsWith("thinking.");
    if (thinking || type.startsWith("message.")) {
      String key =
          (thinking ? "thinking:" : "message:")
              + payload.path(thinking ? "thinkingId" : "messageId").asText();
      if (!thinking
          && payload.hasNonNull("role")
          && !"assistant".equalsIgnoreCase(payload.path("role").asText())) ignoredMessages.add(key);
      if (ignoredMessages.contains(key)) {
        if (type.endsWith(".completed")) ignoredMessages.remove(key);
        return;
      }
      if (!key.equals(active)) {
        flush();
        active = key;
        String time =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(timestamp);
        output.accept("=== " + (thinking ? "thinking" : "answer") + " (" + time + ") ===");
      }
      if (type.endsWith(".delta")) {
        streamed.add(key);
        append(payload.path("delta").asText());
      } else if (type.endsWith(".completed")) {
        if (!streamed.remove(key)) append(payload.path("text").asText());
        flush();
        active = null;
      } else if (type.endsWith(".started")) {
        // The content itself is displayed by delta/completed events.
      } else {
        flush();
      }
      return;
    }
    if (active != null
        && !type.startsWith("tool.")
        && !type.equals("attention.required")
        && !Set.of(
                "agent.run.completed",
                "agent.run.failed",
                "agent.run.cancelled",
                "application.shutdown.started")
            .contains(type)) {
      notices.add(new Notice(type, payload.deepCopy(), timestamp, projectId));
      return;
    }
    flush();
    renderNotice(type, payload, timestamp, projectId);
  }

  /** 利用者が内容を確認できるよう、通知を表示する。 */
  private void renderNotice(
      String type, JsonNode payload, java.time.Instant timestamp, String project) {
    switch (type) {
      case "checkpoint.saved",
          "checkpoint.reconciled",
          "checkpoint.resumed",
          "checkpoint.revision.saved" -> {
        return;
      }
      case "agent.run.started" -> {
        output.accept("[agent] running");
        return;
      }
      case "agent.run.completed" -> {
        output.accept(
            "[agent] completed ("
                + seconds(payload.path("duration"))
                + " s, "
                + (payload.hasNonNull("completionTokens")
                    ? payload.path("completionTokens").asText() + " tokens"
                    : "tokens unavailable")
                + ", "
                + metric(payload, "timeToFirstTokenMillis", "TTFT ", " ms", "TTFT unavailable")
                + ", "
                + metric(
                    payload,
                    "outputTokensPerSecond",
                    "output ",
                    " tok/s",
                    "output speed unavailable")
                + ", "
                + metric(
                    payload,
                    "endToEndTokensPerSecond",
                    "end-to-end ",
                    " tok/s",
                    "end-to-end speed unavailable")
                + ")");
        llmFeatures.clear();
        return;
      }
      case "agent.run.failed", "agent.run.cancelled" -> {
        boolean cancelled =
            type.endsWith("cancelled")
                || payload.path("error").path("code").asText().equals("cancelled");
        output.accept(
            "[agent] "
                + (cancelled ? "cancelled: " : "failed: ")
                + errorReason(payload.path("error")));
        llmFeatures.clear();
        return;
      }
      case "llm.request.started" -> {
        llmFeatures.put(payload.path("requestId").asText(), payload.path("feature").asText());
        output.accept("[llm] request sent (" + payload.path("feature").asText() + ")");
        return;
      }
      case "llm.request.failed" -> {
        llmFeatures.remove(payload.path("requestId").asText());
        output.accept(
            "[llm] request failed ("
                + payload.path("durationMs").asLong()
                + " ms): "
                + errorReason(payload.path("error")));
        return;
      }
      case "llm.response.first_token" -> {
        output.accept("[llm] first token (" + payload.path("durationMs").asLong() + " ms)");
        return;
      }
      case "llm.response.completed" -> {
        String id = payload.path("requestId").asText();
        String feature = llmFeatures.remove(id);
        output.accept(
            "[llm] response received ("
                + (feature == null ? id : feature)
                + ", "
                + payload.path("durationMs").asLong()
                + " ms)");
        return;
      }
      case "tool.started" -> {
        String arguments =
            payload.path("argumentsSummary").asText("").replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        output.accept(
            "  → "
                + payload.path("toolName").asText()
                + (arguments.isBlank() ? "" : " " + arguments));
        return;
      }
      case "tool.completed" -> {
        output.accept(
            "  ✓ "
                + payload.path("toolName").asText()
                + " ("
                + payload.path("duration").asLong()
                + " ms)");
        return;
      }
      case "skill.selection.started" -> {
        output.accept("[skill] selecting");
        return;
      }
      case "skill.selection.completed" -> {
        payload.path("warnings").forEach(warning -> output.accept(warning.asText()));
        var names = new ArrayList<String>();
        payload
            .path("explicitSkillNames")
            .forEach(name -> names.add(name.asText() + " (explicit)"));
        payload
            .path("implicitSkillNames")
            .forEach(name -> names.add(name.asText() + " (implicit)"));
        output.accept("[skill] selected: " + (names.isEmpty() ? "none" : String.join(", ", names)));
        return;
      }
      case "skill.selection.failed" -> {
        output.accept("[skill] selection failed: " + errorReason(payload.path("error")));
        return;
      }
      case "skill.routing.started" -> {
        int invocation = payload.path("routingInvocation").asInt();
        output.accept(
            "[skill] selecting from "
                + payload.path("candidateCount").asInt()
                + " skills..."
                + (invocation > 1 ? " (#" + invocation + ")" : ""));
        return;
      }
      case "skill.routing.completed" -> {
        payload.path("warnings").forEach(warning -> output.accept(warning.asText()));
        String selection =
            payload.hasNonNull("selectedSkill")
                ? payload.path("selectedSkill").asText() + " selected"
                : "no skill selected";
        output.accept(
            "[skill] "
                + selection
                + " from "
                + payload.path("candidateCount").asInt()
                + " skills ("
                + duration(payload.path("durationMs").asLong())
                + (payload.hasNonNull("selectorDurationMs")
                    ? ", selector " + duration(payload.path("selectorDurationMs").asLong())
                    : "")
                + ")");
        return;
      }
      case "skill.routing.failed" -> {
        output.accept(
            "[skill] selection failed after "
                + duration(payload.path("durationMs").asLong())
                + ": "
                + errorReason(payload.path("error")).replaceAll("\\s+", " ").strip());
        return;
      }
      case "skill.candidates.evaluated" -> {
        boolean selected = payload.path("selected").asBoolean();
        output.accept(
            "[skill-candidate] "
                + payload.path("totalSkillCount").asInt()
                + " -> "
                + payload.path("candidateCount").asInt()
                + " skills ("
                + payload.path("durationMs").asLong()
                + "ms), actual="
                + (selected ? payload.path("actualSelectedSkill").asText() : "none")
                + ", top5="
                + (!selected ? "n/a" : payload.path("top5Hit").asBoolean() ? "hit" : "miss"));
        return;
      }
      case "subagent.started", "subagent.completed", "subagent.failed", "subagent.cancelled" -> {
        output.accept(
            "[subagent] "
                + payload.path("agentId").asText()
                + " "
                + payload.path("status").asText().toLowerCase(java.util.Locale.ROOT)
                + " ("
                + seconds(payload.path("duration"))
                + " s) ["
                + payload.path("subAgentRunId").asText()
                + "]");
        if (payload.hasNonNull("failureReason"))
          output.accept(
              "[subagent] 原因: "
                  + dev.mikoto2000.rei.event.CredentialRedactor.redact(
                      payload.path("failureReason").asText()));
        return;
      }
      default -> {}
    }
    if (Set.of("context.compression.failed", "context.compression.skipped").contains(type)) {
      String state = type.endsWith(".failed") ? "failed" : "skipped";
      String details = payload.path("details").asText("");
      String suffix =
          details.isBlank()
              ? ""
              : " (" + dev.mikoto2000.rei.event.FailureMessages.text(details) + ")";
      output.accept(
          "[context.compression] "
              + state
              + ": "
              + dev.mikoto2000.rei.event.FailureMessages.text(payload.path("reason").asText(""))
              + suffix);
      return;
    }
    if ("tool.failed".equals(type)) {
      output.accept(
          "  ✗ " + payload.path("toolName").asText() + ": " + errorReason(payload.path("error")));
      return;
    }
    if ("attention.required".equals(type)) {
      if ("APPROVAL_REQUIRED".equals(payload.path("kind").asText())) {
        approvalOutput.accept(payload.path("message").asText());
        return;
      }
      output.accept(
          "[attention] "
              + payload.path("kind").asText()
              + ": "
              + payload.path("message").asText()
              + " (/attention show "
              + payload.path("attentionId").asText("null")
              + ")");
      return;
    }
    shellNotices.accept(type, payload, timestamp, project);
  }

  private static String seconds(JsonNode duration) {
    return String.format(java.util.Locale.ROOT, "%.1f", duration.asLong() / 1_000d);
  }

  private static String duration(long millis) {
    return millis < 1_000
        ? millis + "ms"
        : String.format(java.util.Locale.ROOT, "%.2fs", millis / 1_000d);
  }

  private static String metric(
      JsonNode payload, String field, String prefix, String suffix, String fallback) {
    return payload.hasNonNull(field)
        ? prefix
            + String.format(java.util.Locale.ROOT, "%.1f", payload.path(field).asDouble())
            + suffix
        : fallback;
  }

  /** 軽量CLIでの対話で用いるエラー理由を返す。 */
  static String errorReason(JsonNode error) {
    String message = error.path("message").asText("");
    if (message.isBlank()) message = error.path("errorType").asText(error.path("type").asText(""));
    String code = error.path("code").asText("");
    if (!code.isBlank()) message = "[" + code + "] " + message;
    return dev.mikoto2000.rei.event.FailureMessages.text(message);
  }

  /** 既存の記録へ経緯を残すため、実行イベント出力を追記する。 */
  private void append(String text) {
    pending.append(text);
    int newline;
    while ((newline = pending.indexOf("\n")) >= 0) {
      String line = pending.substring(0, newline);
      output.accept(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
      pending.delete(0, newline + 1);
    }
  }

  /** 軽量CLIでの対話に関する保存の確定を処理する。 */
  void flush() {
    if (!pending.isEmpty()) {
      output.accept(pending.toString());
      pending.setLength(0);
    }
    active = null;
    for (var notice : notices)
      renderNotice(notice.type(), notice.payload(), notice.timestamp(), notice.project());
    notices.clear();
  }
}
