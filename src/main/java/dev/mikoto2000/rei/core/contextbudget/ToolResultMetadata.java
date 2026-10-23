package dev.mikoto2000.rei.core.contextbudget;

import dev.mikoto2000.rei.review.ReviewTools;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * 承認の保存境界を越えて維持する、登録済みツール結果の配信情報。 任意のメッセージ属性を永続化せず、配信方式・元ページ位置・配信確認票だけを扱う。
 *
 * @param policies 呼び出しごとの登録済み配信方式
 * @param pages 元結果のページ位置
 * @param receipts レビュー本文の配信確認票
 */
public record ToolResultMetadata(
    Map<String, ResultDelivery.Kind> policies,
    Map<String, ToolResultCompressor.PagedResult> pages,
    Map<String, ReviewTools.Receipt> receipts) {
  /** 旧形式で欠ける項目を空にし、保存対象を不変にする。 */
  public ToolResultMetadata {
    policies = policies == null ? Map.of() : Map.copyOf(policies);
    pages = pages == null ? Map.of() : Map.copyOf(pages);
    receipts = receipts == null ? Map.of() : Map.copyOf(receipts);
  }

  /** 実際の応答に対応する型付き配信情報だけを取得する。 */
  public static ToolResultMetadata capture(ToolResponseMessage message) {
    Set<String> ids =
        message.getResponses().stream()
            .map(ToolResponseMessage.ToolResponse::id)
            .collect(Collectors.toSet());
    var metadata = message.getMetadata();
    return new ToolResultMetadata(
        selected(metadata.get("rei.deliveryPolicies"), ResultDelivery.Kind.class, ids),
        selected(metadata.get("rei.losslessPages"), ToolResultCompressor.PagedResult.class, ids),
        selected(metadata.get("rei.reviewReceipts"), ReviewTools.Receipt.class, ids));
  }

  /** 配信処理が認識できる型のままメッセージ属性を復元する。 */
  public Map<String, Object> restore() {
    var metadata = new HashMap<String, Object>();
    if (!policies.isEmpty()) metadata.put("rei.deliveryPolicies", policies);
    if (!pages.isEmpty()) metadata.put("rei.losslessPages", pages);
    if (!receipts.isEmpty()) metadata.put("rei.reviewReceipts", receipts);
    return Map.copyOf(metadata);
  }

  /** 不明な属性や文字列による偽装を登録済み属性に変換しない。 */
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
