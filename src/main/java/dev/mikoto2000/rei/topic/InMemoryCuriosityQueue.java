package dev.mikoto2000.rei.topic;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class InMemoryCuriosityQueue implements CuriosityQueue {
  private final Map<String, CuriosityItem> items = new LinkedHashMap<>();
  private final Map<String, String> normalizedQuestionIndex = new LinkedHashMap<>();

  @Override
  public synchronized void add(CuriosityItem item) {
    String normalized = normalize(item.question());
    if (normalizedQuestionIndex.containsKey(normalized)) return;
    items.put(item.id(), item);
    normalizedQuestionIndex.put(normalized, item.id());
  }

  @Override
  public synchronized List<CuriosityItem> findCandidates(CuriosityQuery query) {
    Instant now = query.now();
    int limit = Math.max(0, query.limit());
    if (limit == 0) return List.of();
    return items.values().stream()
        .filter(item -> item.status() == CuriosityStatus.PENDING)
        .filter(item -> item.expiresAt() == null || item.expiresAt().isAfter(now))
        .sorted(Comparator.comparingDouble(CuriosityItem::priority).reversed()
            .thenComparing(CuriosityItem::createdAt))
        .limit(limit)
        .toList();
  }

  @Override
  public synchronized void markUsed(String id) {
    updateStatus(id, CuriosityStatus.USED);
  }

  @Override
  public synchronized void dismiss(String id) {
    updateStatus(id, CuriosityStatus.DISMISSED);
  }

  private void updateStatus(String id, CuriosityStatus status) {
    CuriosityItem item = items.get(id);
    if (item == null) return;
    items.put(id, item.withStatus(status));
  }

  List<CuriosityItem> all() {
    return new ArrayList<>(items.values());
  }

  static String normalize(String value) {
    if (value == null) return "";
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", "")
        .trim();
  }
}
