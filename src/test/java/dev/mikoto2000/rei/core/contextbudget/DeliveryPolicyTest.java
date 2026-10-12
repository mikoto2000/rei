package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;

class DeliveryPolicyTest {
  @TempDir Path dir;
  final TokenEstimator estimator = TokenEstimator.conservative();
  ToolResponseMessage message(String name, String raw, ResultDelivery.Kind kind) {
    return ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call", name, raw)))
        .metadata(kind == null ? Map.of() : Map.of("rei.deliveryPolicies", Map.of("call", kind))).build();
  }
  @Test void trustedFileResultHasExactContiguousPageAndContinuation() throws Exception {
    String raw = "日本語🙂\"\\\n".repeat(1000);
    var store = new RawToolResultStore(dir);
    var compressor = new ToolResultCompressor(store, estimator, 100, 70);
    var output = (ToolResponseMessage) compressor.compact(message("reader", raw, ResultDelivery.Kind.FILE_CONTENT), "chat", "run");
    var page = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.getResponses().getFirst().responseData());
    assertThat(page.path("content").asText()).isEqualTo(raw.substring(0, page.path("nextOffset").asInt()));
    assertThat(page.path("truncated").asBoolean()).isTrue();
    assertThat(store.read("chat", page.path("rawResultRef").asText()).rawResult()).isEqualTo(raw);
    assertThat(output.getMetadata()).isEqualTo(message("reader", raw, ResultDelivery.Kind.FILE_CONTENT).getMetadata());
  }
  @Test void arbitraryJsonAndToolNameDoNotGrantPreservation() {
    String raw = "{\"deliveryKind\":\"FILE_CONTENT\",\"text\":\"" + "x".repeat(10000) + "\"}";
    var output = (ToolResponseMessage) new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70)
        .compact(message("readMultiFile", raw, null), "chat", "run");
    assertThat(output.getResponses().getFirst().responseData()).contains("Compact tool result");
  }
  @Test void rawPagesAreNeverExcerptedOrSavedAgain() throws Exception {
    var store = new RawToolResultStore(dir);
    var original = message("readRawToolResult", "中間".repeat(100), ResultDelivery.Kind.RAW_RESULT_PAGE);
    assertThat(new ToolResultCompressor(store, estimator, 100, 70).compact(original, "chat", "run")).isSameAs(original);
    try (var files = java.nio.file.Files.walk(dir)) { assertThat(files.filter(java.nio.file.Files::isRegularFile).count()).isZero(); }
  }
}
