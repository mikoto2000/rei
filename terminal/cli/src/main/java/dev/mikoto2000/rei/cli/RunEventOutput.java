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
  private final Set<String> streamed = new HashSet<>();
  private final StringBuilder pending = new StringBuilder();
  private String active;
  private record Notice(String type, JsonNode payload) { }
  private final List<Notice> notices = new ArrayList<>();

  RunEventOutput(Consumer<String> output) { this.output = output; }

  void accept(String type, JsonNode payload) {
    boolean thinking = type.startsWith("thinking.");
    if (thinking || type.startsWith("message.")) {
      String key = (thinking ? "thinking:" : "message:")
          + payload.path(thinking ? "thinkingId" : "messageId").asText();
      if (!key.equals(active)) {
        flush();
        active = key;
        if (thinking) output.accept("[thinking]");
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
    if("subagent.failed".equals(type)) {
      output.accept("[subagent] " + payload.path("agentId").asText() + " " + payload.path("status").asText()+" ["+payload.path("subAgentRunId").asText()+"]");
      output.accept("[subagent] 原因: " + payload.path("failureReason").asText("失敗理由がありません。返却結果を確認してください。"));
      return;
    }
    if ("attention.required".equals(type)) {
      output.accept("[attention] " + payload.path("kind").asText());
      payload.path("message").asText().lines().forEach(output);
      return;
    }
    output.accept(type + ": " + payload);
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
