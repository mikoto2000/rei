package dev.mikoto2000.rei.topic;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 話題の管理に必要な入力先記憶情報への関心実行待ちを扱う。 */
public class InMemoryCuriosityQueue implements CuriosityQueue {
  /** 項目一覧。 */
  private final Map<String, CuriosityItem> items = new LinkedHashMap<>();

  /** 表記を統一した質問位置。 */
  private final Map<String, String> normalizedQuestionIndex = new LinkedHashMap<>();

  /** 新たな対象を登録し、後から利用できるようにする。 */
  @Override
  public synchronized void add(CuriosityItem item) {
    String normalized = normalize(item.question());
    if (normalizedQuestionIndex.containsKey(normalized)) return;
    items.put(item.id(), item);
    normalizedQuestionIndex.put(normalized, item.id());
  }

  /** 対象を特定できるよう、候補一覧を探す。 */
  @Override
  public synchronized List<CuriosityItem> findCandidates(CuriosityQuery query) {
    Instant now = query.now();
    int limit = Math.max(0, query.limit());
    if (limit == 0) return List.of();
    return items.values().stream()
        .filter(item -> item.status() == CuriosityStatus.PENDING)
        .filter(item -> item.expiresAt() == null || item.expiresAt().isAfter(now))
        .sorted(
            Comparator.comparingDouble(CuriosityItem::priority)
                .reversed()
                .thenComparing(CuriosityItem::createdAt))
        .limit(limit)
        .toList();
  }

  /** 後続の判断に現在の状態を反映するため、使用量の状態を記録する。 */
  @Override
  public synchronized void markUsed(String id) {
    updateStatus(id, CuriosityStatus.USED);
  }

  /** 話題の管理に関するdismissを処理する。 */
  @Override
  public synchronized void dismiss(String id) {
    updateStatus(id, CuriosityStatus.DISMISSED);
  }

  /** 最新の内容を後続の処理に反映するため、状態を更新する。 */
  private void updateStatus(String id, CuriosityStatus status) {
    CuriosityItem item = items.get(id);
    if (item == null) return;
    items.put(id, item.withStatus(status));
  }

  /** 話題の管理で用いる全対象を返す。 */
  List<CuriosityItem> all() {
    return new ArrayList<>(items.values());
  }

  /** 同じ意味の指定を共通に扱えるよう、表記や形式をそろえる。 */
  static String normalize(String value) {
    if (value == null) return "";
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", "")
        .trim();
  }
}
