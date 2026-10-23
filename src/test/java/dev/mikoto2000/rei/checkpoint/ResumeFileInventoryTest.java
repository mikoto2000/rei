package dev.mikoto2000.rei.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("integration")
class ResumeFileInventoryTest {
  @TempDir Path root;

  @SuppressWarnings("unchecked")
  @ParameterizedTest
  @ValueSource(
      strings = {
        "valid",
        "mismatch",
        "outside",
        "tooMany",
        "tooLarge",
        "malformed",
        "empty",
        "smallBudget",
        "changed",
        "unknown",
        "missingReconciliation",
        "differentRunReference",
        "missingReference"
      })
  void inventoryRequiresVerifiedBoundedProjectPathsAndCompleteDelivery(String condition)
      throws Exception {
    var json = new ObjectMapper().findAndRegisterModules();
    var settings = new CheckpointProperties();
    if (condition.equals("smallBudget")) settings.setContextCharacters(1024);
    var raw = new RawToolResultStore(root.resolve("raw"));
    String body =
        switch (condition) {
          case "outside" -> "[\"../outside.md\"]";
          case "tooMany" ->
              json.writeValueAsString(
                  java.util.stream.IntStream.range(0, 100)
                      .mapToObj(i -> "docs/" + i + ".md")
                      .toList());
          case "tooLarge" -> json.writeValueAsString(List.of("x".repeat(300)));
          case "malformed" -> "not JSON";
          case "empty" -> "[]";
          default -> "[\"docs/intro.md\",\"docs/security.md\"]";
        };
    String ref =
        raw.save(
            "chat",
            "old",
            condition.equals("mismatch") ? "readMultiFile" : "listFile",
            "inventory",
            body);
    var tree =
        (ObjectNode)
            json.valueToTree(
                PersistentCheckpoint.initial("task", "A", "chat", "old", root, "Review the docs")
                    .resume("resumed"));
    tree.set(
        "operations",
        json.valueToTree(
            List.of(
                Map.of(
                    "toolCallId",
                    "inventory",
                    "toolName",
                    "listFile",
                    "status",
                    "SUCCEEDED",
                    "eventId",
                    "event",
                    "runId",
                    "old"))));
    tree.set(
        "evidence",
        json.valueToTree(
            List.of(
                Map.of(
                    "origin",
                    "RAW_RESULT_REFERENCE",
                    "summary",
                    ref,
                    "toolCallId",
                    "inventory",
                    "runId",
                    "old"))));
    tree.set(
        "reconciliation",
        json.valueToTree(
            Map.of(
                "decision",
                "CONTINUE",
                "usable",
                List.of(),
                "changed",
                List.of(),
                "recheck",
                List.of(),
                "unknownOperations",
                List.of(),
                "blockers",
                List.of(),
                "nextAction",
                "Continue")));
    if (condition.equals("changed"))
      ((ObjectNode) tree.path("reconciliation"))
          .set("changed", json.valueToTree(List.of("docs/intro.md")));
    if (condition.equals("missingReconciliation")) tree.putNull("reconciliation");
    if (condition.equals("unknown"))
      ((com.fasterxml.jackson.databind.node.ArrayNode) tree.path("operations"))
          .add(
              json.valueToTree(
                  Map.of(
                      "toolCallId",
                      "unknown",
                      "toolName",
                      "runCommand",
                      "status",
                      "UNKNOWN",
                      "eventId",
                      "unknown-event",
                      "runId",
                      "old")));
    if (condition.equals("differentRunReference"))
      ((ObjectNode) tree.path("evidence").get(0)).put("runId", "different");
    if (condition.equals("missingReference")) tree.set("evidence", json.createArrayNode());
    if (condition.equals("smallBudget")) tree.put("request", "large request ".repeat(1000));
    var saved = json.treeToValue(tree, PersistentCheckpoint.class);
    try (var service =
        new PersistentCheckpointService(
            null,
            null,
            settings,
            new InMemoryAgentEventBus(),
            null,
            null,
            null,
            null,
            null,
            null,
            null)) {
      ((Map<String, PersistentCheckpoint>) ReflectionTestUtils.getField(service, "active"))
          .put("resumed", saved);
      ReflectionTestUtils.setField(service, "rawResults", raw);
      String before = json.writeValueAsString(saved);
      long writes = raw.bodyWriteCount();
      var request =
          ChatClientRequest.builder().prompt(new Prompt(new UserMessage("Continue"))).build();
      Prompt sent;
      try (var scope = AgentRunScope.open(new AgentRunContext("resumed", "chat", root, "A"))) {
        sent = new ResumeContextAdvisor(service).before(request, null).prompt();
      }
      var handoff =
          sent.getInstructions().stream()
              .filter(m -> Boolean.TRUE.equals(m.getMetadata().get("rei.resumeContext")))
              .findFirst()
              .orElseThrow();
      boolean delivered = Boolean.TRUE.equals(handoff.getMetadata().get("rei.knownFileInventory"));
      assertThat(delivered).isEqualTo(condition.equals("valid"));
      if (delivered) {
        assertThat(handoff.getText())
            .contains(
                "knownFileInventory:",
                "docs/intro.md",
                "docs/security.md",
                ref,
                "completeProjectInventory");
        assertThat(handoff.getMetadata()).containsEntry("rei.resumeRunId", "resumed");
      }
      assertThat(handoff.getText().length()).isLessThanOrEqualTo(settings.getContextCharacters());
      assertThat(json.writeValueAsString(saved)).isEqualTo(before);
      assertThat(raw.bodyWriteCount()).isEqualTo(writes);
    }
  }
}
