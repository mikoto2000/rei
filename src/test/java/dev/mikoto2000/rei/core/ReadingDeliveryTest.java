package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.tooldelivery.ReadingDelivery;

import dev.mikoto2000.rei.tooldelivery.ReadingStateStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class ReadingDeliveryTest {
  @TempDir Path root;

  @Test
  void oldRestorationMetadataIsBoundedWithoutAbortingAConfirmedPrompt() throws Exception {
    Files.writeString(root.resolve("a.txt"), "\n".repeat(64000));
    Path data = root.resolve("data");
    try (var gate = new StorageMigrationCoordinator(data)) { gate.prepare(); }
    var store = ReadingTestStores.store(new StorageDatabase(data));
    var raw = new RawToolResultStore(data);
    var owner = new AgentRunContext("bounded-delivery", "chat", root, "project");
    var tools = new Tools();
    var budgets = new dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties();
    budgets.setPageTargetTokens(16000);
    budgets.setPageMaxTokens(16000);
    tools.configureDelivery(dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative(), budgets);
    try (var scope = AgentRunScope.open(owner)) {
      var initial = tools.readMultiFile(List.of(new Tools.ReadFileRequest("a.txt", 1, 1000)), root).getFirst();
      String lineHash = FileSnapshots.hash(new byte[0]);
      var old = java.util.stream.IntStream.range(0, 60).mapToObj(i ->
          new ReadingDelivery.Entry("a.txt", initial.version(), i * 1000 + 1,
              java.util.Collections.nCopies(1000, lineHash), "old-" + i)).toList();
      store.save(owner, "delivery", "evidence", new ReadingDelivery.Saved(old), java.time.Duration.ofDays(1));
      var requests = java.util.stream.IntStream.range(60, 64).mapToObj(i -> new Tools.ReadFileRequest("a.txt", i * 1000 + 1, (i + 1) * 1000)).toList();
      var result = tools.readMultiFile(requests, root);
      assertTrue(result.stream().allMatch(file -> file.content().size() == 1000));
      var assistant = AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("bounded-call", "function", "readMultiFile", StorageDatabase.JSON.writeValueAsString(java.util.Map.of("files", requests))))).build();
      var response = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("bounded-call", "readMultiFile", StorageDatabase.JSON.writeValueAsString(result)))).build();
      var delivery = ReadingTestStores.delivery(store, raw);
      assertDoesNotThrow(() -> delivery.confirm(owner, new Prompt(List.of(assistant, response))));
      var saved = store.load(owner, "delivery", "evidence", ReadingDelivery.Saved.class);
      assertTrue(StorageDatabase.JSON.writeValueAsBytes(saved).length <= 2 * 1024 * 1024);
    }
  }

  @Test
  void onlyConfirmedCurrentPromptCanSuppressAndCompressionRequiresRestoration() throws Exception {
    Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree\n");
    Path data = root.resolve("data");
    try (var gate = new StorageMigrationCoordinator(data)) {
      gate.prepare();
    }
    var store = ReadingTestStores.store(new StorageDatabase(data));
    var raw = new RawToolResultStore(data);
    var delivery = ReadingTestStores.delivery(store, raw);
    var tools = new Tools();
    tools.setReadingDelivery(delivery);
    var owner = new AgentRunContext("delivery", "chat", root, "project");
    try (var scope = AgentRunScope.open(owner)) {
      var request =
          StorageDatabase.JSON.readValue(
              "{\"path\":\"a.txt\",\"startLine\":1,\"endLine\":2,\"unreadOnly\":true}",
              Tools.ReadFileRequest.class);
      var first = tools.readMultiFile(List.of(request), root);
      var evaluator = new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(root);
      evaluator.afterTool(
          "readMultiFile",
          "{}",
          StorageDatabase.JSON.writeValueAsString(first),
          evaluator.beforeTool("readMultiFile", "{}"));
      assertEquals(2, first.getFirst().content().size());
      var assistant =
          AssistantMessage.builder()
              .content("")
              .toolCalls(
                  List.of(
                      new AssistantMessage.ToolCall(
                          "call",
                          "function",
                          "readMultiFile",
                          "{\"files\":[{\"path\":\"a.txt\",\"startLine\":1,\"endLine\":2}]}")))
              .build();
      var response =
          ToolResponseMessage.builder()
              .responses(
                  List.of(
                      new ToolResponseMessage.ToolResponse(
                          "call", "readMultiFile", StorageDatabase.JSON.writeValueAsString(first))))
              .build();
      delivery.confirm(owner, new Prompt(List.of(assistant, response)));
      var repeated = tools.readMultiFile(List.of(request), root).getFirst();
      assertTrue(repeated.content().isEmpty());
      assertTrue(repeated.delivery().suppressed());
      assertEquals("AVAILABLE", repeated.delivery().state());
      var suppressedResponse =
          ToolResponseMessage.builder()
              .responses(
                  List.of(
                      new ToolResponseMessage.ToolResponse(
                          "call",
                          "readMultiFile",
                          StorageDatabase.JSON.writeValueAsString(List.of(repeated)))))
              .build();
      var rescued =
          delivery.restoreSuppressed(owner, new Prompt(List.of(assistant, suppressedResponse)));
      String rescuedJson =
          ((ToolResponseMessage) rescued.getInstructions().getLast())
              .getResponses()
              .getFirst()
              .responseData();
      assertEquals(
          "one", StorageDatabase.JSON.readTree(rescuedJson).get(0).path("content").get(0).asText());
      delivery.confirm(owner, new Prompt(List.of(new UserMessage("compressed summary"))));
      var restored = tools.readMultiFile(List.of(request), root).getFirst();
      assertEquals(List.of("one", "two"), restored.content());
      assertEquals("RESTORABLE", restored.delivery().state());
      assertTrue(
          evaluator
              .afterTool(
                  "readMultiFile",
                  "{}",
                  StorageDatabase.JSON.writeValueAsString(List.of(restored)),
                  evaluator.beforeTool("readMultiFile", "{}"))
              .stream()
              .anyMatch(
                  e ->
                      e.kind()
                          == dev.mikoto2000.rei.core.stagnation.ProgressEvent.CONTEXT_RESTORED));
      assertFalse(
          evaluator
              .afterTool(
                  "readMultiFile",
                  "{}",
                  StorageDatabase.JSON.writeValueAsString(List.of(restored)),
                  evaluator.beforeTool("readMultiFile", "{}"))
              .stream()
              .anyMatch(
                  e ->
                      e.kind()
                          == dev.mikoto2000.rei.core.stagnation.ProgressEvent.CONTEXT_RESTORED));
      assertNotNull(restored.delivery().evidence().getFirst().rawResultRef());
      tools.setReadingDelivery(
          ReadingTestStores.delivery(ReadingTestStores.store(new StorageDatabase(data)), raw));
      assertEquals(
          List.of("one", "two"), tools.readMultiFile(List.of(request), root).getFirst().content());
      Files.writeString(root.resolve("a.txt"), "changed\ntwo\nthree\n");
      assertEquals(
          "STALE", tools.readMultiFile(List.of(request), root).getFirst().delivery().state());
    }
  }
}
