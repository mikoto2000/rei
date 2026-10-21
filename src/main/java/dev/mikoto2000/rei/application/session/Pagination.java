package dev.mikoto2000.rei.application.session;

import java.util.List;
import java.util.function.Function;

/** 会話の継続管理に必要なPaginationを扱う。 */
public final class Pagination {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private Pagination() {}

  /** 会話の継続管理で用いる上限を返す。 */
  public static int limit(Integer requested) {
    int limit = requested == null ? 50 : requested;
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("limit must be between 1 and 100");
    return limit;
  }

  /** 会話の継続管理で用いるページを返す。 */
  public static <T> HistoryPage<T> page(List<T> rows, int limit, Function<T, String> cursor) {
    boolean more = rows.size() > limit;
    var items = rows.subList(0, Math.min(limit, rows.size()));
    return new HistoryPage<>(items, more ? cursor.apply(items.getLast()) : null);
  }
}
