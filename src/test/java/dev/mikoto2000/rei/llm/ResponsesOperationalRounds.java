package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.event.AgentEventType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Existing app ToolLoop drives every iteration; this class is a bounded owned data oracle. */
final class ResponsesOperationalRounds {
  private final ResponsesOperationalLiveAcceptance test;
  private final ResponsesAcceptanceRuntime runtime;
  private final int pairs;
  private final Path directory;
  private final String relative;
  private final String fact = "fact-" + java.util.UUID.randomUUID();
  private final String[] readProofs;
  private final String[] writeProofs;
  private final int[] values;
  private final int[] totals;
  private int completed;
  private int reads;
  private boolean awaitingWrite;

  ResponsesOperationalRounds(ResponsesOperationalLiveAcceptance test, String level, int pairs)
      throws Exception {
    this.test = test;
    runtime = test.runtime;
    this.pairs = pairs;
    if (!Map.of("short", 3, "medium", 10, "long", 25).getOrDefault(level, 0).equals(pairs))
      throw new IllegalArgumentException("Predeclared operation level required");
    directory = runtime.root.resolve("operational-" + level);
    Files.createDirectory(directory); // A completed prefix is never silently overwritten/restarted.
    relative = "operational-" + level + "/progress.txt";
    readProofs = new String[pairs + 1];
    writeProofs = new String[pairs];
    values = new int[pairs];
    totals = new int[pairs + 1];
    var random = new java.security.SecureRandom();
    for (int i = 0; i <= pairs; i++) readProofs[i] = java.util.UUID.randomUUID().toString();
    for (int i = 0; i < pairs; i++) {
      writeProofs[i] = java.util.UUID.randomUUID().toString();
      values[i] = 1 + random.nextInt(99);
      totals[i + 1] = totals[i] + values[i];
    }
  }

  private String content(int index) {
    return (index + 1) + "|" + totals[index + 1] + "|" + writeProofs[index];
  }

  private Map<String, Object> writeArguments(int index, boolean reverse) {
    var file = new LinkedHashMap<String, Object>();
    if (reverse) file.put("content", content(index));
    file.put("path", relative);
    if (!reverse) file.put("content", content(index));
    return Map.of("files", List.of(file));
  }

  private void record(String operation) throws java.io.IOException {
    Files.writeString(directory.resolve("operations.jsonl"),
        ResponsesAcceptanceRuntime.JSON.writeValueAsString(Map.of("operation", operation,
            "reads", reads, "writes", completed)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  private ToolCallback read() {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name("now")
            .description("Owned sequential fixture READ, not time. Supply step (1-based), prior running total and read proof. Read once, then write once. The first response contains a fact that must be retained to the final answer.")
            .inputSchema("{\"type\":\"object\",\"properties\":{\"step\":{\"type\":\"integer\"},\"total\":{\"type\":\"integer\"},\"proof\":{\"type\":\"string\"}},\"required\":[\"step\",\"total\",\"proof\"],\"additionalProperties\":false}")
            .build();
      }
      public String call(String input) {
        synchronized (ResponsesOperationalRounds.this) {
        try {
          var args = ResponsesAcceptanceRuntime.JSON.readTree(input);
          if (completed >= pairs || awaitingWrite || args.size() != 3
              || args.path("step").asInt() != completed + 1
              || args.path("total").asInt(-1) != totals[completed]
              || !readProofs[completed].equals(args.path("proof").asText()))
            throw new IllegalStateException("Owned read order/proof/bound rejected");
          awaitingWrite = true;
          reads++;
          record("READ");
          var result = new LinkedHashMap<String, Object>();
          result.put("step", completed + 1);
          result.put("value", values[completed]);
          result.put("writeProof", writeProofs[completed]);
          if (completed == 0) result.put("retainFirstFact", fact);
          result.put("ownedHistoricalDetail", "Synthetic owned historical detail. ".repeat(64));
          return ResponsesAcceptanceRuntime.JSON.writeValueAsString(result);
        } catch (java.io.IOException failure) {
          throw new java.io.UncheckedIOException(failure);
        }
        }
      }
    };
  }

  private ToolCallback write(ToolCallback actual) {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name("writeMultiFile")
            .description("Approval-required owned progress write. One files item only, exact path and content step|newRunningTotal|writeProof. Use compact JSON arguments, no spaces outside strings. Calls execute Rei's actual filesystem tool.")
            .inputSchema("{\"type\":\"object\",\"properties\":{\"files\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":1,\"items\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"],\"additionalProperties\":false}}},\"required\":[\"files\"],\"additionalProperties\":false}")
            .build();
      }
      public String call(String input) {
        throw new IllegalStateException("Actual write requires the owned ToolContext");
      }
      public String call(String input, ToolContext context) {
        synchronized (ResponsesOperationalRounds.this) {
        try {
          if (!awaitingWrite || completed >= pairs
              || !ResponsesAcceptanceRuntime.JSON.readTree(input).equals(
                  ResponsesAcceptanceRuntime.JSON.valueToTree(writeArguments(completed, false))))
            throw new IllegalStateException("Owned write path/content/order rejected");
          actual.call(input, context);
          if (!content(completed).equals(Files.readString(directory.resolve("progress.txt"))))
            throw new IllegalStateException("Actual owned filesystem result mismatch");
          completed++;
          awaitingWrite = false;
          record("WRITE");
          return ResponsesAcceptanceRuntime.JSON.writeValueAsString(Map.of("written", true,
              "completedPairs", completed, "nextReadProof", readProofs[completed]));
        } catch (java.io.IOException failure) {
          throw new java.io.UncheckedIOException(failure);
        }
        }
      }
    };
  }

  void run(String level) throws Exception {
    var owner = test.owner(level);
    runtime.properties.updateOutputLimit(limits -> limits.setMaxLlmCallsPerRun(
        level.equals("short") ? 8 : level.equals("medium") ? 27 : 64));
    var approvals = test.app.getBean(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class);
    // Finite exact encodings of the same owned effects; no broad auto-approval or parser change.
    for (int i = 0; i < pairs; i++) for (boolean reverse : List.of(false, true)) {
      String compact = ResponsesAcceptanceRuntime.JSON.writeValueAsString(writeArguments(i, reverse));
      String pretty = ResponsesAcceptanceRuntime.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(writeArguments(i, reverse));
      String spaced = compact.replace("\":", "\": ").replace(",\"", ", \"");
      for (String input : java.util.Set.of(compact, pretty, spaced)) {
        var request = approvals.request("writeMultiFile", input, owner);
        approvals.decide(owner.projectId(), request.id(), true);
      }
    }
    var actual = java.util.Arrays.stream(org.springframework.ai.support.ToolCallbacks.from(
        test.app.getBean(dev.mikoto2000.rei.core.Tools.class)))
        .filter(callback -> callback.getToolDefinition().name().equals("writeMultiFile"))
        .findFirst().orElseThrow();
    var catalog = dev.mikoto2000.rei.llm.discovery.ToolCatalog.standard(List.of(read(), write(actual)));
    test.tools.callbacks = List.of(catalog.callback("now"), catalog.callback("writeMultiFile"));
    var compressions = new ArrayList<Map<String, Object>>();
    var subscription = test.app.getBean(dev.mikoto2000.rei.event.AgentEventBus.class).subscribe(event -> {
      if (owner.runId().equals(event.runId()) && event.type() == AgentEventType.CONTEXT_COMPRESSION_COMPLETED
          && event.payload() instanceof dev.mikoto2000.rei.event.ContextCompressionPayload c)
        compressions.add(Map.of("before", c.beforeEstimatedTokens(), "after", c.afterEstimatedTokens(),
            "messages", c.compressedMessageCount(), "through", c.summaryThroughSequence()));
    });
    var initialBudget = runtime.budget.snapshot();
    long started = System.nanoTime();
    long heapBefore = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    try {
      var result = test.execute(owner,
          "Execute exactly " + pairs + " sequential READ/WRITE pairs (" + (2 * pairs) + " tool operations). "
          + "Only one tool per response. Start now with step=1,total=0,proof=" + readProofs[0] + ". "
          + "For each now result add its value to the running total; writeMultiFile files=[{path:\"" + relative
          + "\",content:\"step|newRunningTotal|writeProof\"}] using the actual numeric step, computed total and returned writeProof. "
          + "Emit compact JSON without spaces outside strings. After write, call now for the next step using returned nextReadProof and updated total. "
          + "The next proof is unknown until that operation returns, so never batch calls. Remember retainFirstFact from the first READ even after summaries. "
          + "After the last successful write, stop tools and return exactly OPERATIONS_" + (2 * pairs)
          + " total=<computed total> fact=<retainFirstFact> with no markdown or other text.");
      boolean success = result.success() && completed == pairs && reads == pairs && !awaitingWrite
          && ("OPERATIONS_" + (2 * pairs) + " total=" + totals[pairs] + " fact=" + fact).equals(result.text().trim())
          && (!level.equals("long") || compressions.size() >= 2);
      var fields = new LinkedHashMap<String, Object>();
      fields.put("success", success); fields.put("level", level);
      fields.put("reads", reads); fields.put("writes", completed);
      fields.put("requested_operations", 2 * pairs); fields.put("compression", compressions);
      fields.put("duration_ms", (System.nanoTime() - started) / 1000000.0);
      fields.put("heap_before", heapBefore);
      fields.put("heap_after", java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
      fields.put("new_requests", runtime.budget.snapshot().calls() - initialBudget.calls());
      fields.put("budget", runtime.budget.snapshot());
      fields.put("payload_usage", test.app.getBean(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.class)
          .usage(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(owner), owner.runId()));
      fields.put("run_status", test.app.getBean(dev.mikoto2000.rei.application.run.RunRegistry.class)
          .get(owner.runId()).status().name());
      runtime.report("APP_ROUNDS", fields);
      if (!success) throw new IllegalStateException("Owned operational round criterion failed");
    } finally {
      subscription.unsubscribe();
    }
  }
}
