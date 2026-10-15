package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;

import dev.mikoto2000.rei.core.chat.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

@Tag("integration")
class RawResultPageContractTest {
  @TempDir Path dir;
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper();

  @Test
  void registeredToolLoopReturnsPlainHeadersAndExactUnicodeTextRatherThanJsonString() {
    var store = new RawToolResultStore(dir);
    String raw = "指摘🙂\\\"\r\n".repeat(30);
    String ref = store.save("chat", "run", "delegateTask", "child-call", raw);
    var callbacks =
        DeliveryToolRegistration.provider(new RawToolResultTools(store)).getToolCallbacks();
    var prompt =
        new Prompt(
            "request", ToolCallingChatOptions.builder().toolCallbacks(List.of(callbacks)).build());
    var call =
        new AssistantMessage.ToolCall(
            "page-call",
            "function",
            "readRawToolResult",
            "{\"rawResultRef\":\"" + ref + "\",\"offset\":0,\"limit\":16000}");
    var response =
        new ChatResponse(
            List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build())));
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat", dir))) {
      var history = new ToolLoopSupport().execute(prompt, response).conversationHistory();
      var result = (ToolResponseMessage) history.getLast();
      String data = result.getResponses().getFirst().responseData();
      assertThat(data).startsWith("toolName: delegateTask\n").doesNotStartWith("\"");
      var page = RawResultPage.parse(data);
      assertThat(page.content()).isEqualTo(raw);
      assertThat(page.rawResultRef()).isEqualTo(ref);
      assertThat(page.nextOffset()).isEqualTo(raw.length());
      assertThat(page.hasMore()).isFalse();
      assertThat(ToolResultCompressor.policy(result, "page-call"))
          .isEqualTo(ResultDelivery.Kind.RAW_RESULT_PAGE);
    }
  }

  @Test
  void legacyQuotedPagesAreUnwrappedOnceAndInvalidCursorMetadataHasSafeDiagnostic()
      throws Exception {
    var store = new RawToolResultStore(dir);
    String raw = "original🙂\r\nrawResultRef: body-is-not-provenance\n";
    String ref = store.save("chat", "run", "delegateTask", "child-call", raw);
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat", dir))) {
      var plain = new RawToolResultTools(store).readRawToolResult(ref, 0, 16000);
      assertThat(RawResultPage.parse(json.writeValueAsString(plain)).text()).isEqualTo(plain);
      assertThat(RawResultPage.parse(plain).content()).isEqualTo(raw);
      String invalid = plain.replace("nextOffset: " + raw.length(), "nextOffset: 999999");
      for (String bad :
          List.of(
              invalid,
              json.writeValueAsString(json.writeValueAsString(plain)),
              "secret-body\nrawResultRef: " + ref,
              "{\"rawResultRef\":\"" + ref + "\",\"secret\":\"private\"}")) {
        assertThatThrownBy(() -> RawResultPage.parse(bad))
            .isInstanceOfSatisfying(
                RawResultPageException.class,
                e -> {
                  var diagnostic =
                      dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.safeError(e);
                  assertThat(diagnostic.code()).isEqualTo("raw_page_invalid_provenance");
                  assertThat(diagnostic.message()).doesNotContain("secret-body", "private", ref);
                  assertThat(e.getCause()).isNull();
                });
      }
    }
  }

  @Test
  void finalBudgetFitReadsLegacyQuotedEnvelopeAndPreservesExactOffset() throws Exception {
    var store = new RawToolResultStore(dir);
    var estimator = TokenEstimator.conservative();
    String raw = "x".repeat(10) + "指摘🙂\\\"\r\n".repeat(4000);
    String ref = store.save("chat", "run", "delegateTask", "child-call", raw);
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat", dir))) {
      String legacy =
          json.writeValueAsString(new RawToolResultTools(store).readRawToolResult(ref, 10, 16000));
      var response = new ToolResponseMessage.ToolResponse("page-call", "readRawToolResult", legacy);
      var message =
          ToolResponseMessage.builder()
              .responses(List.of(response))
              .metadata(
                  Map.of(
                      "rei.deliveryPolicies",
                      Map.of("page-call", ResultDelivery.Kind.RAW_RESULT_PAGE)))
              .build();
      var projected =
          new ToolResultCompressor(store, estimator, 8000, 2000)
              .fit(new Prompt(message), "chat", "run", 900);
      assertThat(ContextBudgetManager.estimateMessages(projected.getInstructions(), estimator))
          .isLessThanOrEqualTo(900);
      var page =
          RawResultPage.parse(
              ((ToolResponseMessage) projected.getInstructions().getFirst())
                  .getResponses()
                  .getFirst()
                  .responseData());
      assertThat(page.offset()).isEqualTo(10);
      assertThat(page.rawResultRef()).isEqualTo(ref);
      assertThat(page.content()).isEqualTo(raw.substring(page.offset(), page.nextOffset()));
    }
  }

  @Test
  void unwrappingLegacyQuotedPagesCannotExtendTheOriginalRequestedSpan() throws Exception {
    var store = new RawToolResultStore(dir);
    var estimator = TokenEstimator.conservative();
    String raw = "\\".repeat(10000);
    String ref = store.save("chat", "run", "delegateTask", "child-call", raw);
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat", dir))) {
      String original = new RawToolResultTools(store).readRawToolResult(ref, 0, 1200);
      String legacy = json.writeValueAsString(original);
      var message =
          ToolResponseMessage.builder()
              .responses(
                  List.of(
                      new ToolResponseMessage.ToolResponse(
                          "page-call", "readRawToolResult", legacy)))
              .metadata(
                  Map.of(
                      "rei.deliveryPolicies",
                      Map.of("page-call", ResultDelivery.Kind.RAW_RESULT_PAGE)))
              .build();
      assertThat(ContextBudgetManager.estimateMessages(List.of(message), estimator))
          .isGreaterThan(540);
      var projected =
          new ToolResultCompressor(store, estimator, 8000, 2000)
              .fit(new Prompt(message), "chat", "run", 540);
      var page =
          RawResultPage.parse(
              ((ToolResponseMessage) projected.getInstructions().getFirst())
                  .getResponses()
                  .getFirst()
                  .responseData());
      assertThat(page.nextOffset()).isLessThanOrEqualTo(RawResultPage.parse(original).nextOffset());
      assertThat(page.content()).isEqualTo(raw.substring(page.offset(), page.nextOffset()));
      assertThat(ContextBudgetManager.estimateMessages(projected.getInstructions(), estimator))
          .isLessThanOrEqualTo(540);
    }
  }
}
