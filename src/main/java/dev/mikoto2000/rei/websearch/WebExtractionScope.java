package dev.mikoto2000.rei.websearch;

/** Original user query only; external page text never becomes planning instructions. */
final class WebExtractionScope implements AutoCloseable {
  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
  private final String previous;
  private WebExtractionScope(String query) { previous = CURRENT.get(); if (query == null) CURRENT.remove(); else CURRENT.set(query); }
  static String query() { return CURRENT.get(); }
  static WebExtractionScope enter(String query) { return new WebExtractionScope(query); }
  public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
