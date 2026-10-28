package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.*;

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
  void onlyConfirmedCurrentPromptCanSuppressAndCompressionRequiresRestoration() throws Exception {
    Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree\n");
    Path data = root.resolve("data");
    try (var gate = new StorageMigrationCoordinator(data)) { gate.prepare(); }
    var store = new ReadingStateStore(new StorageDatabase(data));
    var raw = new RawToolResultStore(data);
    var delivery = new ReadingDelivery(store, raw);
    var tools = new Tools();
    tools.setReadingDelivery(delivery);
    var owner = new AgentRunContext("delivery", "chat", root, "project");
    try (var scope = AgentRunScope.open(owner)) {
      var request = StorageDatabase.JSON.readValue("{\"path\":\"a.txt\",\"startLine\":1,\"endLine\":2,\"unreadOnly\":true}", Tools.ReadFileRequest.class);
      var first = tools.readMultiFile(List.of(request), root);
      assertEquals(2, first.getFirst().content().size());
      var assistant = AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", "readMultiFile", "{\"files\":[{\"path\":\"a.txt\",\"startLine\":1,\"endLine\":2}]}"))).build();
      var response = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call", "readMultiFile", StorageDatabase.JSON.writeValueAsString(first)))).build();
      delivery.confirm(owner, new Prompt(List.of(assistant, response)));
      var repeated = tools.readMultiFile(List.of(request), root).getFirst();
      assertTrue(repeated.content().isEmpty());
      assertTrue(repeated.delivery().suppressed());
      assertEquals("AVAILABLE", repeated.delivery().state());
      var suppressedResponse = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call", "readMultiFile", StorageDatabase.JSON.writeValueAsString(List.of(repeated))))).build();
      var rescued = delivery.restoreSuppressed(owner, new Prompt(List.of(assistant, suppressedResponse)));
      String rescuedJson = ((ToolResponseMessage) rescued.getInstructions().getLast()).getResponses().getFirst().responseData();
      assertEquals("one", StorageDatabase.JSON.readTree(rescuedJson).get(0).path("content").get(0).asText());
      delivery.confirm(owner, new Prompt(List.of(new UserMessage("compressed summary"))));
      var restored = tools.readMultiFile(List.of(request), root).getFirst();
      assertEquals(List.of("one", "two"), restored.content());
      assertEquals("RESTORABLE", restored.delivery().state());
      assertNotNull(restored.delivery().evidence().getFirst().rawResultRef());
      tools.setReadingDelivery(new ReadingDelivery(new ReadingStateStore(new StorageDatabase(data)), raw));
      assertEquals(List.of("one", "two"), tools.readMultiFile(List.of(request), root).getFirst().content());
      Files.writeString(root.resolve("a.txt"), "changed\ntwo\nthree\n");
      assertEquals("STALE", tools.readMultiFile(List.of(request), root).getFirst().delivery().state());
    }
  }
}
