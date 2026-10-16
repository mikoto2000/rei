package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

class DeliveryPolicyTest {
  @TempDir Path dir;
  final TokenEstimator estimator = TokenEstimator.conservative();

  ToolResponseMessage message(String name, String raw, ResultDelivery.Kind kind) {
    return ToolResponseMessage.builder()
        .responses(List.of(new ToolResponseMessage.ToolResponse("call", name, raw)))
        .metadata(kind == null ? Map.of() : Map.of("rei.deliveryPolicies", Map.of("call", kind)))
        .build();
  }

  @Test
  void trustedFileResultHasExactContiguousPageAndContinuation() throws Exception {
    String raw = "日本語🙂\"\\\n".repeat(1000);
    var store = new RawToolResultStore(dir);
    var compressor = new ToolResultCompressor(store, estimator, 100, 70);
    var output =
        (ToolResponseMessage)
            compressor.compact(
                message("reader", raw, ResultDelivery.Kind.FILE_CONTENT), "chat", "run");
    var page =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(output.getResponses().getFirst().responseData());
    assertThat(page.path("content").asText())
        .isEqualTo(raw.substring(0, page.path("nextOffset").asInt()));
    assertThat(page.path("truncated").asBoolean()).isTrue();
    assertThat(store.read("chat", page.path("rawResultRef").asText()).rawResult()).isEqualTo(raw);
    assertThat(output.getMetadata())
        .containsAllEntriesOf(
            message("reader", raw, ResultDelivery.Kind.FILE_CONTENT).getMetadata());
    assertThat(output.getMetadata()).containsKey("rei.losslessPages");
  }

  @Test
  void boundedNativeResultKeepsItsSchemaAndNextRead() {
    String raw =
        "{\"content\":\""
            + "x".repeat(10000)
            + "\",\"nextRead\":{\"path\":\"source\",\"offset\":10000}}";
    assertThat(estimator.text(raw)).isBetween(2049, 4096);
    var original = message("reader", raw, ResultDelivery.Kind.FILE_CONTENT);
    assertThat(
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 8000, 2000)
                .compact(original, "chat", "run"))
        .isSameAs(original);
  }

  @Test
  void arbitraryJsonAndToolNameDoNotGrantPreservation() {
    String raw = "{\"deliveryKind\":\"FILE_CONTENT\",\"text\":\"" + "x".repeat(10000) + "\"}";
    var output =
        (ToolResponseMessage)
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70)
                .compact(message("readMultiFile", raw, null), "chat", "run");
    assertThat(output.getResponses().getFirst().responseData()).contains("Compact tool result");
  }

  @Test
  void rawPagesAreNeverExcerptedOrSavedAgain() throws Exception {
    var store = new RawToolResultStore(dir);
    var original =
        message("readRawToolResult", "中間".repeat(100), ResultDelivery.Kind.RAW_RESULT_PAGE);
    assertThat(new ToolResultCompressor(store, estimator, 100, 70).compact(original, "chat", "run"))
        .isSameAs(original);
    try (var files = java.nio.file.Files.walk(dir)) {
      assertThat(files.filter(java.nio.file.Files::isRegularFile).count()).isZero();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"delegateTask", "delegateTasks"})
  void delegatedJsonCanBeRecoveredExactlyInOrderWithoutHeadTailExcerpts(String tool)
      throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    String raw =
        json.writeValueAsString(
            Map.of(
                "status",
                "COMPLETED",
                "findings",
                List.of("first🙂", "教材の指摘\"\\\n".repeat(4000), "middle-finding", "final-finding")));
    var store = new RawToolResultStore(dir);
    var output =
        (ToolResponseMessage)
            new ToolResultCompressor(store, estimator, 100, 70)
                .compact(message(tool, raw, ResultDelivery.Kind.LOSSLESS_RESULT), "chat", "run");
    String body = output.getResponses().getFirst().responseData();
    assertThat(body)
        .doesNotContain("Compact tool result", "importantOutput:", "\nhead:", "\ntail:");
    var page = json.readTree(body);
    String ref = page.path("rawResultRef").asText();
    assertThat(page.path("truncated").asBoolean()).isTrue();
    assertThat(page.path("content").asText())
        .isEqualTo(raw.substring(0, page.path("nextOffset").asInt()));
    assertThat(store.read("chat", ref).rawResult()).isEqualTo(raw);
    var collected = new StringBuilder(page.path("content").asText());
    int offset = page.path("nextOffset").asInt();
    while (offset < raw.length()) {
      String continuation =
          new RawToolResultTools(store, estimator)
              .pageText(ref, store.read("chat", ref), offset, 16000, 2048);
      String[] parts = continuation.split("\n", 8);
      assertThat(parts[3]).isEqualTo("offset: " + offset);
      int next = Integer.parseInt(parts[5].substring("nextOffset: ".length()));
      assertThat(next).isGreaterThan(offset);
      assertThat(parts[7]).isEqualTo(raw.substring(offset, next));
      collected.append(parts[7]);
      offset = next;
    }
    assertThat(collected.toString()).isEqualTo(raw);
    assertThat(json.readTree(collected.toString()).path("findings").get(2).asText())
        .isEqualTo("middle-finding");
    assertThat(output.getMetadata()).containsKey("rei.losslessPages");
  }

  @Test
  void delegatedResultBelowPageLimitKeepsOriginalJsonEvenAboveGeneralThreshold() {
    String raw = "{\"findings\":[\"" + "日本語".repeat(100) + "\"]}";
    var original = message("delegateTask", raw, ResultDelivery.Kind.LOSSLESS_RESULT);
    assertThat(
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70)
                .compact(original, "chat", "run"))
        .isSameAs(original);
  }

  @Test
  void delegatedPagesCanShrinkToFinalInputBudgetWithoutLosingNativeResult() throws Exception {
    String raw = "{\"findings\":[\"" + "教材🙂".repeat(5000) + "\"]}";
    var store = new RawToolResultStore(dir);
    var compressor = new ToolResultCompressor(store, estimator, 100, 70);
    var projected =
        compressor.compact(
            message("delegateTask", raw, ResultDelivery.Kind.LOSSLESS_RESULT), "chat", "run");
    var prompt =
        compressor.fit(
            new org.springframework.ai.chat.prompt.Prompt(
                List.of(new UserMessage("review"), projected)),
            "chat",
            "run",
            600);
    assertThat(ContextBudgetManager.estimateMessages(prompt.getInstructions(), estimator))
        .isLessThanOrEqualTo(600);
    var tool = (ToolResponseMessage) prompt.getInstructions().getLast();
    var page =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(tool.getResponses().getFirst().responseData());
    assertThat(page.path("content").asText())
        .isEqualTo(raw.substring(0, page.path("nextOffset").asInt()));
    assertThat(page.path("truncated").asBoolean()).isTrue();
    assertThat(store.read("chat", page.path("rawResultRef").asText()).rawResult()).isEqualTo(raw);
  }

  @Test
  void toolNameAndJsonCannotClaimLosslessDelegationDelivery() {
    String raw =
        "{\"deliveryKind\":\"LOSSLESS_RESULT\",\"findings\":[\"" + "教材".repeat(5000) + "\"]}";
    var output =
        (ToolResponseMessage)
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70)
                .compact(message("delegateTask", raw, null), "chat", "run");
    assertThat(output.getResponses().getFirst().responseData()).contains("Compact tool result");
  }
}
