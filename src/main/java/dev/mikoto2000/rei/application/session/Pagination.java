package dev.mikoto2000.rei.application.session;

import java.util.List;
import java.util.function.Function;

public final class Pagination {
  private Pagination() {}
  public static int limit(Integer requested) {
    int limit = requested == null ? 50 : requested;
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
    return limit;
  }
  public static <T> HistoryPage<T> page(List<T> rows, int limit, Function<T, String> cursor) {
    boolean more = rows.size() > limit;
    var items = rows.subList(0, Math.min(limit, rows.size()));
    return new HistoryPage<>(items, more ? cursor.apply(items.getLast()) : null);
  }
}
