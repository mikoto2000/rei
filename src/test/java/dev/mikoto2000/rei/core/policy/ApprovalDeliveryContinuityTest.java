package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.ResultDelivery;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.core.contextbudget.ToolResultCompressor;
import dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.review.ReviewCoverageLedger;
import dev.mikoto2000.rei.review.ReviewTools;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** 承認の保存境界を越えて本文と配信証拠を維持する契約を検証する。 */
@Tag("integration")
class ApprovalDeliveryContinuityTest {
  /** 実データと隔離した保存場所。 */
  @TempDir Path directory;

  /** 実行の消費済み予算。 */
  final OutputLimitRunBudget.Snapshot budget =
      new OutputLimitRunBudget.Snapshot(1, 436, 12345, false, 2, 3);

  /** 保存後に新しいストアで読み直す。 */
  ToolResponseMessage roundTrip(ToolResponseMessage message) {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("approval.db"));
    var store = new ApprovalContinuationStore(source);
    String id = UUID.randomUUID().toString();
    store.save(
        id,
        new AgentRunContext("run", "session", directory, "project"),
        "review",
        List.of(new UserMessage("review"), message),
        new RunContinuation.PendingTools(
            "save assessment",
            List.of(new AssistantMessage.ToolCall("write", "function", "writeMultiFile", "{}")),
            List.of()),
        budget,
        false);
    var reopened = new ApprovalContinuationStore(source);
    var restored = reopened.saved(reopened.find(id).orElseThrow());
    assertThat(restored.budget()).isEqualTo(budget);
    return (ToolResponseMessage) restored.messages().getLast();
  }

  /** 登録済み配信属性を持つ本文を作る。 */
  ToolResponseMessage body(ResultDelivery.Kind kind, String text) {
    return ToolResponseMessage.builder()
        .responses(List.of(new ToolResponseMessage.ToolResponse("read", "readMultiFile", text)))
        .metadata(Map.of("rei.deliveryPolicies", Map.of("read", kind)))
        .build();
  }

  /** 本文や元結果のページを一般ログの抜粋へ変えない。 */
  @ParameterizedTest
  @EnumSource(
      value = ResultDelivery.Kind.class,
      names = {"FILE_CONTENT", "LOSSLESS_RESULT", "RAW_RESULT_PAGE"})
  void approvalPreservesExactDeliveredBody(ResultDelivery.Kind kind) {
    String text = "章の本文と末尾の検証事項\n".repeat(300);
    var original = body(kind, text);
    var restored = roundTrip(original);
    var compressor =
        new ToolResultCompressor(
            new RawToolResultStore(directory.resolve("raw")),
            TokenEstimator.conservative(),
            100,
            70);
    var delivery = new ToolResultDeliveryProperties();
    delivery.setPageMaxTokens(12000);
    compressor.setDeliveryProperties(delivery);
    var result = (ToolResponseMessage) compressor.compact(restored, "session", "run");
    assertThat(result.getResponses()).isEqualTo(original.getResponses());
    assertThat(ToolResultCompressor.policy(restored, "read")).isEqualTo(kind);
  }

  /** ページ位置が復元後も元本文の参照を指し続ける。 */
  @Test
  void approvalPreservesOriginalPageReferenceWhenContextShrinks() {
    var compressor =
        new ToolResultCompressor(
            new RawToolResultStore(directory.resolve("raw")),
            TokenEstimator.conservative(),
            100,
            70);
    var delivery = new ToolResultDeliveryProperties();
    delivery.setPageMinTokens(64);
    delivery.setPageTargetTokens(400);
    delivery.setPageMaxTokens(500);
    compressor.setDeliveryProperties(delivery);
    var paged =
        (ToolResponseMessage)
            compressor.compact(
                body(ResultDelivery.Kind.FILE_CONTENT, "本文\n".repeat(9000)), "session", "run");
    var restored = roundTrip(paged);
    var expected = compressor.fit(new Prompt(List.of(paged)), "session", "run", 300);
    var actual = compressor.fit(new Prompt(List.of(restored)), "session", "run", 300);
    assertThat(restored.getMetadata().get("rei.losslessPages"))
        .isEqualTo(paged.getMetadata().get("rei.losslessPages"));
    assertThat(((ToolResponseMessage) actual.getInstructions().getFirst()).getResponses())
        .isEqualTo(((ToolResponseMessage) expected.getInstructions().getFirst()).getResponses());
  }

  /** レビューの配信確認票を型を保って復元する。 */
  @Test
  void approvalPreservesTypedReviewReceipt() {
    var receipt =
        new ReviewTools.Receipt(
            "job",
            new ReviewCoverageLedger.Range(
                "lesson.md", "v1", new ReviewCoverageLedger.Span(0, 4, 1, 1, 0, 4), "hash"),
            "serialized");
    var message =
        ToolResponseMessage.builder()
            .responses(
                List.of(new ToolResponseMessage.ToolResponse("read", "readReviewFile", "body")))
            .metadata(
                Map.of(
                    "rei.deliveryPolicies",
                    Map.of("read", ResultDelivery.Kind.FILE_CONTENT),
                    "rei.reviewReceipts",
                    Map.of("read", receipt)))
            .build();
    var restored = (Map<?, ?>) roundTrip(message).getMetadata().get("rei.reviewReceipts");
    assertThat(restored.get("read"))
        .isInstanceOf(dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.ReceiptValue.class);
    assertThat(
            ((dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.ReceiptValue) restored.get("read"))
                .serializedHash())
        .isEqualTo(receipt.serializedHash());
  }

  /** 属性のない旧形式も一般結果として復元する。 */
  @Test
  void legacyMessagesKeepConservativeGeneralPolicy() throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("legacy.db"));
    var store = new ApprovalContinuationStore(source);
    String document =
        "{\"owner\":{\"runId\":\"run\",\"conversationId\":\"session\",\"projectRoot\":\".\",\"projectId\":\"project\",\"requestSource\":\"SHELL\"},\"request\":\"review\",\"history\":[{\"kind\":\"TOOL\",\"text\":\"\",\"calls\":[],\"responses\":[{\"id\":\"read\",\"name\":\"readMultiFile\",\"responseData\":\"body\"}]}],\"pending\":null,\"budget\":null,\"managedParent\":false}";
    var restored =
        store.saved(
            new ApprovalContinuationStore.Entry(
                "legacy", "run", "WAITING_APPROVAL", document, 0, null));
    assertThat(
            ToolResultCompressor.policy(
                (ToolResponseMessage) restored.messages().getFirst(), "read"))
        .isEqualTo(ResultDelivery.Kind.GENERAL);
    assertThat(json.readTree(document).get("history").get(0).has("delivery")).isFalse();
  }

  /** 任意のメタデータや文字列の偽装属性を永続化しない。 */
  @Test
  void untypedAndUnrelatedMetadataDoesNotGainDeliveryAuthority() {
    var message =
        ToolResponseMessage.builder()
            .responses(
                List.of(new ToolResponseMessage.ToolResponse("read", "readMultiFile", "body")))
            .metadata(
                Map.of(
                    "rei.deliveryPolicies",
                    Map.of("read", "FILE_CONTENT"),
                    "arbitrary",
                    "untrusted"))
            .build();
    var restored = roundTrip(message);
    assertThat(ToolResultCompressor.policy(restored, "read"))
        .isEqualTo(ResultDelivery.Kind.GENERAL);
    assertThat(restored.getMetadata()).doesNotContainKey("arbitrary");
  }
}
