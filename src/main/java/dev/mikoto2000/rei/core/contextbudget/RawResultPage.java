package dev.mikoto2000.rei.core.contextbudget;

/**
 * Parses only application-registered RAW_RESULT_PAGE responses, including legacy JSON-string
 * wrapping.
 */
public record RawResultPage(
    String text,
    String rawResultRef,
    int offset,
    int nextOffset,
    int totalCharacters,
    boolean hasMore,
    String content) {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public static RawResultPage parse(String response) {
    try {
      String text = response;
      if (text != null && text.stripLeading().startsWith("\"")) {
        var node = JSON.readTree(text);
        if (node == null || !node.isTextual()) throw new IllegalArgumentException();
        text = node.textValue();
      }
      if (text == null) throw new IllegalArgumentException();
      var parts = text.split("\n", 8);
      if (parts.length != 8) throw new IllegalArgumentException();
      String[] keys = {
        "toolName: ",
        "toolCallId: ",
        "rawResultRef: ",
        "offset: ",
        "totalCharacters: ",
        "nextOffset: ",
        "hasMore: "
      };
      for (int i = 0; i < keys.length; i++)
        if (!parts[i].startsWith(keys[i])) throw new IllegalArgumentException();
      String ref = parts[2].substring(keys[2].length());
      if (!java.util.UUID.fromString(ref).toString().equals(ref))
        throw new IllegalArgumentException();
      int start = Integer.parseInt(parts[3].substring(keys[3].length()));
      int total = Integer.parseInt(parts[4].substring(keys[4].length()));
      int next = Integer.parseInt(parts[5].substring(keys[5].length()));
      String more = parts[6].substring(keys[6].length());
      if (!more.equals("true") && !more.equals("false")) throw new IllegalArgumentException();
      boolean hasMore = Boolean.parseBoolean(more);
      if (start < 0
          || next < start
          || next > total
          || next - start != parts[7].length()
          || hasMore != (next < total)
          || hasMore && next == start) throw new IllegalArgumentException();
      return new RawResultPage(text, ref, start, next, total, hasMore, parts[7]);
    } catch (java.io.IOException | RuntimeException invalid) {
      throw new RawResultPageException(RawResultPageException.Reason.INVALID_PROVENANCE);
    }
  }
}
