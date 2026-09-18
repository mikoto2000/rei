package dev.mikoto2000.rei.core.contextbudget;

import java.util.*;
import org.springframework.ai.chat.messages.*;

/** Deterministic representation: selected diagnostic lines plus head/tail; the raw body is always saved first. */
public class ToolResultCompressor {
  private final RawToolResultStore store;
  private final TokenEstimator estimator;
  private final int threshold, tokens;
  public ToolResultCompressor(RawToolResultStore store, TokenEstimator estimator, int threshold, int tokens) {
    this.store = store; this.estimator = estimator; this.threshold = threshold; this.tokens = tokens;
  }
  public void preserve(Message message, String conversation, String run) {
    if (message instanceof ToolResponseMessage tool) for (var response : tool.getResponses())
      store.save(conversation, run, response.name(), response.id(), Objects.toString(response.responseData(), ""));
  }
  public Message compact(Message message, String conversation, String run) {
    if (!(message instanceof ToolResponseMessage tool)) return message;
    var responses = new ArrayList<ToolResponseMessage.ToolResponse>();
    boolean changed = false;
    for (var response : tool.getResponses()) {
      String raw = Objects.toString(response.responseData(), "");
      String ref = store.save(conversation, run, response.name(), response.id(), raw);
      if (estimator.text(raw) <= threshold) { responses.add(response); continue; }
      changed = true;
      String header = "[Compact tool result; omitted output is available via readRawToolResult]\n"
          + "toolName: " + response.name() + "\ntoolCallId: " + response.id() + "\nrawResultRef: " + ref + "\n";
      String diagnostics = important(raw);
      String compact = header + "importantOutput:\n" + bounded(diagnostics, tokens / 2)
          + "\nhead:\n" + bounded(raw, tokens / 4) + "\ntail:\n"
          + bounded(raw.substring(Math.max(0, raw.length() - tokens)), tokens / 4);
      responses.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(), compact));
      org.slf4j.LoggerFactory.getLogger(getClass()).debug("Tool result compacted: before={}, after={}",
          estimator.text(raw), estimator.text(compact));
    }
    return changed ? ToolResponseMessage.builder().responses(responses).metadata(tool.getMetadata()).build() : message;
  }
  private String important(String raw) {
    // JSON command results frequently put escaped stdout/stderr in a single physical line.
    try {
      var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
      if (node != null && node.isObject()) {
        var text = new StringBuilder();
        for (String key : List.of("command", "status", "exitCode", "exit_code", "error", "stderr", "stdout", "output")) {
          var value = node.get(key);
          if (value != null) text.append(key).append(": ").append(value.asText()).append('\n');
        }
        if (!text.isEmpty()) raw = text.toString();
      }
    } catch (Exception ignored) { }
    return raw.lines().filter(line -> line.toLowerCase(Locale.ROOT)
        .matches(".*(exit.?code|status|command|fail|error|expected|exception|tests run|build |process).*"))
        .limit(80).collect(java.util.stream.Collectors.joining("\n"));
  }
  private String bounded(String text, int budget) {
    int end = text.length();
    while (end > 0 && estimator.text(text.substring(0, end)) > budget) end = end * 3 / 4;
    return text.substring(0, end);
  }
}
