package dev.mikoto2000.rei.tooldelivery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * 保存処理と本文配信処理が共有する、業務機能に依存しない配信情報。
 *
 * @param policies 登録済み配信方式の名前
 * @param pages 元結果のページ位置
 * @param receipts 実際に渡したレビュー本文の確認用ハッシュ
 */
public record ToolResultMetadata(
    Map<String, String> policies,
    Map<String, Page> pages,
    Map<String, Receipt> receipts,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        Map<String, FileReadBudget> fileReadBudgets) {
  public interface FileReadBudgetValue {
    int tokens();
  }

  public record FileReadBudget(int tokens) implements FileReadBudgetValue {}

  public ToolResultMetadata(
      Map<String, String> policies, Map<String, Page> pages, Map<String, Receipt> receipts) {
    this(policies, pages, receipts, Map.of());
  }

  /** 配信方式が保存処理へ公開する名前。 */
  public interface PolicyValue {
    /** 登録済みの配信方式名を返す。 */
    String deliveryKind();
  }

  /** レビュー配信確認に使う本文ハッシュの契約。 */
  public interface ReceiptValue {
    /** 配信されたページ全体のハッシュを返す。 */
    String serializedHash();
  }

  /**
   * 復元した配信方式。
   *
   * @param deliveryKind 登録済みの配信方式名
   */
  public record Policy(String deliveryKind) implements PolicyValue {}

  /**
   * 元本文への継続位置。
   *
   * @param ref 元結果の参照
   * @param offset 文字位置
   */
  public record Page(String ref, int offset) {}

  /**
   * 本文全体のハッシュを維持する。範囲・ジョブは確認時にハッシュ照合済み本文から読む。
   *
   * @param serializedHash 配信されたページ全体のハッシュ
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Receipt(String serializedHash) implements ReceiptValue {}

  /** 欠ける項目を空にし、保存対象を不変にする。 */
  public ToolResultMetadata {
    policies = policies == null ? Map.of() : Map.copyOf(policies);
    pages = pages == null ? Map.of() : Map.copyOf(pages);
    receipts = receipts == null ? Map.of() : Map.copyOf(receipts);
    fileReadBudgets = fileReadBudgets == null ? Map.of() : Map.copyOf(fileReadBudgets);
  }

  /** 実際の応答に対応する型付き配信情報だけを取得する。 */
  public static ToolResultMetadata capture(ToolResponseMessage message) {
    Set<String> ids =
        message.getResponses().stream()
            .map(ToolResponseMessage.ToolResponse::id)
            .collect(Collectors.toSet());
    var metadata = message.getMetadata();
    var policies = new HashMap<String, String>();
    selected(metadata.get("rei.deliveryPolicies"), PolicyValue.class, ids)
        .forEach((id, value) -> policies.put(id, value.deliveryKind()));
    var receipts = new HashMap<String, Receipt>();
    selected(metadata.get("rei.reviewReceipts"), ReceiptValue.class, ids)
        .forEach((id, value) -> receipts.put(id, new Receipt(value.serializedHash())));
    var fileBudgets = new HashMap<String, FileReadBudget>();
    selected(metadata.get("rei.fileReadBudgets"), FileReadBudgetValue.class, ids)
        .forEach((id, value) -> fileBudgets.put(id, new FileReadBudget(value.tokens())));
    return new ToolResultMetadata(
        policies,
        selected(metadata.get("rei.losslessPages"), Page.class, ids),
        receipts,
        fileBudgets);
  }

  /** 配信処理が認識する契約型のまま属性を復元する。 */
  public Map<String, Object> restore() {
    var metadata = new HashMap<String, Object>();
    var restoredPolicies = new HashMap<String, Policy>();
    policies.forEach((id, kind) -> restoredPolicies.put(id, new Policy(kind)));
    if (!restoredPolicies.isEmpty())
      metadata.put("rei.deliveryPolicies", Map.copyOf(restoredPolicies));
    if (!pages.isEmpty()) metadata.put("rei.losslessPages", pages);
    if (!receipts.isEmpty()) metadata.put("rei.reviewReceipts", receipts);
    if (!fileReadBudgets.isEmpty()) metadata.put("rei.fileReadBudgets", fileReadBudgets);
    return Map.copyOf(metadata);
  }

  /** 任意の文字列やメタデータを配信属性へ昇格させない。 */
  private static <T> Map<String, T> selected(Object value, Class<T> type, Set<String> ids) {
    var result = new HashMap<String, T>();
    if (value instanceof Map<?, ?> values)
      values.forEach(
          (key, item) -> {
            if (key instanceof String id && ids.contains(id) && type.isInstance(item))
              result.put(id, type.cast(item));
          });
    return Map.copyOf(result);
  }
}
