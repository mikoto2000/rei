package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatEndpointRemoteCommandsTest {
  @Test
  void metadataAndDirectSelectionUseSharedApiWithoutRequiringConversation() {
    assertEquals(new RemoteCommands.Request("GET", "/api/v1/llm/chat", null),
        RemoteCommands.route(List.of("/llm", "chat", "list"), null, null));
    assertEquals(new RemoteCommands.Request("GET", "/api/v1/llm/chat/current", null),
        RemoteCommands.route(List.of("/llm", "chat", "show"), null, null));
    assertEquals(new RemoteCommands.Request("PUT", "/api/v1/llm/chat/current", Map.of("endpoint", "local")),
        RemoteCommands.route(List.of("/llm", "chat", "use", "local"), null, null));
  }

  @Test
  void interactiveSelectionRequestsMetadataBeforeAnyMutation() {
    assertEquals(new RemoteCommands.Request("GET", "/api/v1/llm/chat", null),
        RemoteCommands.route(List.of("/llm", "chat", "use"), null, null));
  }

  @Test
  void invalidSyntaxAndNamesCannotBecomeAnArbitraryPath() {
    for (var words : List.of(
        List.of("/llm", "chat", "show", "extra"),
        List.of("/llm", "chat", "use", "../../secret"),
        List.of("/llm", "chat", "use", "local", "extra"),
        List.of("/llm", "chat", "unknown")))
      assertThrows(IllegalArgumentException.class, () -> RemoteCommands.route(words, null, null));
  }
}
