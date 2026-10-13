package dev.mikoto2000.rei.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Keeps provider chunk boundaries out of terminal lines and preserves the input prompt. */
final class RunEventOutput {
  private final Consumer<String> output;
  private final Consumer<String> approvalOutput;
  private final Set<String> streamed = new HashSet<>();
  private final Set<String> ignoredMessages = new HashSet<>();
  private final StringBuilder pending = new StringBuilder();
  private String active;
  private record Notice(String type, JsonNode payload) { }
  private final List<Notice> notices = new ArrayList<>();

  RunEventOutput(Consumer<String> output) { this(output, message -> output.accept(dev.mikoto2000.rei.core.completion.ApprovalNotice.text(message))); }
  RunEventOutput(Consumer<String> output, Consumer<String> approvalOutput) { this.output = output; this.approvalOutput = approvalOutput; }

  void accept(String type, JsonNode payload) {
    accept(type, payload, java.time.Instant.now());
  }

  void accept(String type, JsonNode payload, java.time.Instant timestamp) {
    boolean thinking = type.startsWith("thinking.");
    if (thinking || type.startsWith("message.")) {
      String key = (thinking ? "thinking:" : "message:")
          + payload.path(thinking ? "thinkingId" : "messageId").asText();
      if (!thinking && payload.hasNonNull("role") && !"assistant".equalsIgnoreCase(payload.path("role").asText())) ignoredMessages.add(key);
      if (ignoredMessages.contains(key)) {
        if (type.endsWith(".completed")) ignoredMessages.remove(key);
        return;
      }
      if (!key.equals(active)) {
        flush();
        active = key;
        String time = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault()).format(timestamp);
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
    if (active != null && !type.startsWith("tool.") && !type.equals("attention.required")
        && !Set.of("agent.run.completed", "agent.run.failed", "agent.run.cancelled").contains(type)) {
      notices.add(new Notice(type, payload.deepCopy()));
      return;
    }
    flush();
    renderNotice(type, payload);
  }

  private void renderNotice(String type, JsonNode payload) {
    if (Set.of("agent.run.failed", "agent.run.cancelled", "llm.request.failed", "skill.selection.failed",
        "skill.routing.failed", "background_process.failed", "task.failed").contains(type)) {
      output.accept("[" + type + "] 原因: " + errorReason(payload.path("error")));
      return;
    }
    if ("context.compression.failed".equals(type)) {
      output.accept("[context.compression] failed: " + dev.mikoto2000.rei.event.FailureMessages.text(payload.path("reason").asText()));
      return;
    }
    if ("goal.updated".equals(type) && payload.hasNonNull("reason")) {
      output.accept("[goal] " + payload.path("goalId").asText() + " " + payload.path("status").asText()
          + " 理由: " + dev.mikoto2000.rei.event.FailureMessages.text(payload.path("reason").asText()));
      return;
    }
    if("tool.failed".equals(type)) {
      output.accept("✗ " + payload.path("toolName").asText() + ": " + errorReason(payload.path("error")));
      return;
    }
    if("subagent.failed".equals(type)) {
      output.accept("[subagent] " + payload.path("agentId").asText() + " " + payload.path("status").asText()+" ["+payload.path("subAgentRunId").asText()+"]");
      output.accept("[subagent] 原因: " + dev.mikoto2000.rei.event.FailureMessages.text(payload.path("failureReason").asText()));
      return;
    }
    if ("attention.required".equals(type)) {
      if ("APPROVAL_REQUIRED".equals(payload.path("kind").asText())) {
        approvalOutput.accept(payload.path("message").asText()); return;
      }
      output.accept("[attention] " + payload.path("kind").asText());
      payload.path("message").asText().lines().forEach(output);
      return;
    }
    output.accept(type + ": " + payload);
  }

  private static String errorReason(JsonNode error) {
    String message = error.path("message").asText("");
    if (message.isBlank()) message = error.path("errorType").asText("");
    String code = error.path("code").asText("");
    if (!code.isBlank()) message = "[" + code + "] " + message;
    return dev.mikoto2000.rei.event.FailureMessages.text(message);
  }

  private void append(String text) {
    pending.append(text);
    int newline;
    while ((newline = pending.indexOf("\n")) >= 0) {
      String line = pending.substring(0, newline);
      output.accept(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
      pending.delete(0, newline + 1);
    }
  }

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
