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

  /**
   * 軽量CLIでの対話で受け渡す通知を表す。
   *
   * @param type 種別
   * @param payload 通知内容
   */
  private record Notice(String type, JsonNode payload) {}

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
        output.accept(type + ": " + payload);
      }
      return;
    }
    if (active != null
        && !type.startsWith("tool.")
        && !type.equals("attention.required")
        && !Set.of("agent.run.completed", "agent.run.failed", "agent.run.cancelled")
            .contains(type)) {
      notices.add(new Notice(type, payload.deepCopy()));
      return;
    }
    flush();
    renderNotice(type, payload);
  }

  /** 利用者が内容を確認できるよう、通知を表示する。 */
  private void renderNotice(String type, JsonNode payload) {
    if (Set.of(
            "agent.run.failed",
            "agent.run.cancelled",
            "llm.request.failed",
            "skill.selection.failed",
            "skill.routing.failed",
            "background_process.failed",
            "task.failed")
        .contains(type)) {
      output.accept("[" + type + "] 原因: " + errorReason(payload.path("error")));
      return;
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
    if ("goal.updated".equals(type) && payload.hasNonNull("reason")) {
      output.accept(
          "[goal] "
              + payload.path("goalId").asText()
              + " "
              + payload.path("status").asText()
              + " 理由: "
              + dev.mikoto2000.rei.event.FailureMessages.text(payload.path("reason").asText()));
      return;
    }
    if ("tool.failed".equals(type)) {
      output.accept(
          "✗ " + payload.path("toolName").asText() + ": " + errorReason(payload.path("error")));
      return;
    }
    if ("subagent.failed".equals(type)) {
      output.accept(
          "[subagent] "
              + payload.path("agentId").asText()
              + " "
              + payload.path("status").asText()
              + " ["
              + payload.path("subAgentRunId").asText()
              + "]");
      output.accept(
          "[subagent] 原因: "
              + dev.mikoto2000.rei.event.FailureMessages.text(
                  payload.path("failureReason").asText()));
      return;
    }
    if ("attention.required".equals(type)) {
      if ("APPROVAL_REQUIRED".equals(payload.path("kind").asText())) {
        approvalOutput.accept(payload.path("message").asText());
        return;
      }
      output.accept("[attention] " + payload.path("kind").asText());
      payload.path("message").asText().lines().forEach(output);
      return;
    }
    output.accept(type + ": " + payload);
  }

  /** 軽量CLIでの対話で用いるエラー理由を返す。 */
  private static String errorReason(JsonNode error) {
    String message = error.path("message").asText("");
    if (message.isBlank()) message = error.path("errorType").asText("");
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
    for (var notice : notices) renderNotice(notice.type(), notice.payload());
    notices.clear();
  }
}
