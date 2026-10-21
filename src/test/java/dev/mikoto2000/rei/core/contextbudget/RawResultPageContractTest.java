package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** 加工前の結果ページ契約の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class RawResultPageContractTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う保存や受け渡しに用いるデータ形式の変換。 */
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper();

  /** 登録済みツール繰り返しについて、装飾のないHTTPヘッダーを返すことを確認する。 */
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

  /** モデルへ渡す文脈の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
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

  /** 最終予算上限への調整について、旧形式引用済み共通の通知属性を読み取ることを確認する。 */
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

  /** モデルへ渡す文脈の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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
