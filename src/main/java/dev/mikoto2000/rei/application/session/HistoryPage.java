package dev.mikoto2000.rei.application.session;

import java.util.List;

public record HistoryPage<T>(List<T> items, String nextCursor) {
  public HistoryPage { items = List.copyOf(items); }
}
