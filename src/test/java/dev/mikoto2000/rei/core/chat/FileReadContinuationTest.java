package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.contextbudget.ResultDelivery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class FileReadContinuationTest {
  private static final String VERSION = "a".repeat(64);
  private static final String PAGE =
      """
      [{"path":"a.md","version":"%s","truncated":true,
        "nextRead":{"path":"a.md","startLine":117,"endLine":200,
          "offset":48,"expectedVersion":"%s","charset":"UTF-8"}}]
      """
          .formatted(VERSION, VERSION);

  private ToolResponseMessage page(String raw, boolean trusted) {
    return ToolResponseMessage.builder()
        .responses(List.of(new ToolResponseMessage.ToolResponse("read", "readMultiFile", raw)))
        .metadata(
            trusted
                ? Map.of("rei.deliveryPolicies", Map.of("read", ResultDelivery.Kind.FILE_CONTENT))
                : Map.of())
        .build();
  }

  private ToolCall call(String file) {
    return new ToolCall("next", "function", "readMultiFile", "{\"files\":[" + file + "]}");
  }

  @Test
  void inheritsMissingFieldsWhilePreservingRequestedEndAndExplicitCursor() throws Exception {
    var prompt = new Prompt(List.of(new UserMessage("review"), page(PAGE, true)));
    var next =
        FileReadContinuation.inherit(
                prompt, List.of(call("{\"path\":\"a.md\",\"startLine\":117,\"endLine\":300}")))
            .getFirst();
    var file = new ObjectMapper().readTree(next.arguments()).path("files").get(0);
    assertThat(file.path("offset").asInt()).isEqualTo(48);
    assertThat(file.path("expectedVersion").asText()).isEqualTo(VERSION);
    assertThat(file.path("charset").asText()).isEqualTo("UTF-8");
    assertThat(file.path("endLine").asInt()).isEqualTo(300);
    assertThat(FileReadContinuation.inherit(prompt, List.of(next))).containsExactly(next);
  }

  @Test
  void doesNotInferAcrossTurnsOrFromUntrustedOrSupersededResults() {
    var call = call("{\"path\":\"a.md\",\"startLine\":117}");
    for (List<Message> history :
        List.<List<Message>>of(
            List.of(new UserMessage(PAGE)),
            List.of(page(PAGE, false)),
            List.of(page(PAGE, true), new UserMessage("new task")),
            List.of(page(PAGE, true), page("[{\"path\":\"a.md\",\"truncated\":false}]", true)),
            List.of(page(PAGE, true), page("[{\"path\":\"a.md\",\"error\":\"stale\"}]", true)),
            List.of(page(PAGE.replace(VERSION, "invalid"), true)),
            List.of(page("not JSON", true))))
      assertThat(FileReadContinuation.inherit(new Prompt(history), List.of(call)))
          .containsExactly(call);
  }

  @Test
  void doesNotChangeOtherRangesExplicitValuesSelectorsOrAmbiguousCursors() {
    var prompt = new Prompt(List.of(page(PAGE, true)));
    for (var file :
        List.of(
            "{\"path\":\"b.md\",\"startLine\":117}",
            "{\"path\":\"a.md\",\"startLine\":118}",
            "{\"path\":\"a.md\",\"startLine\":117,\"offset\":0}",
            "{\"path\":\"a.md\",\"startLine\":117,\"expectedVersion\":\"other\"}",
            "{\"path\":\"a.md\",\"startLine\":117,\"section\":\"intro\"}")) {
      var call = call(file);
      assertThat(FileReadContinuation.inherit(prompt, List.of(call))).containsExactly(call);
    }
    String ambiguous = PAGE.strip().replaceFirst("\\]$", "," + PAGE.strip().substring(1));
    ambiguous = ambiguous.replaceFirst("\"offset\":48", "\"offset\":49");
    var call = call("{\"path\":\"a.md\",\"startLine\":117}");
    assertThat(
            FileReadContinuation.inherit(new Prompt(List.of(page(ambiguous, true))), List.of(call)))
        .containsExactly(call);
  }
}
