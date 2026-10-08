package dev.mikoto2000.rei.websearch;

import java.util.concurrent.atomic.AtomicInteger;
import dev.mikoto2000.rei.http.HttpFetchException;

/** One quota includes provider attempts, expansions and provider redirects. */
final class SearchRequestBudget implements AutoCloseable {
  private static final ThreadLocal<AtomicInteger> CURRENT = new ThreadLocal<>();
  private final boolean owner;
  private SearchRequestBudget(int maximum) { owner = CURRENT.get() == null; if (owner) CURRENT.set(new AtomicInteger(maximum)); }
  static SearchRequestBudget enter(int maximum) { return new SearchRequestBudget(maximum); }
  static boolean exhausted() { return CURRENT.get() != null && CURRENT.get().get() <= 0; }
  static void request() {
    captureGate().run();
  }
  static Runnable captureGate() {
    var remaining = CURRENT.get();
    return () -> {
    if (remaining != null && remaining.getAndUpdate(value -> Math.max(0, value - 1)) <= 0)
      throw new HttpFetchException(HttpFetchException.Code.REQUEST_BUDGET);
    };
  }
  public void close() { if (owner) CURRENT.remove(); }
}
