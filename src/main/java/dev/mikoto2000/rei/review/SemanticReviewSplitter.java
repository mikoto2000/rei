package dev.mikoto2000.rei.review;

/** Selects a source boundary without changing, dropping or duplicating any characters. */
final class SemanticReviewSplitter {
  private SemanticReviewSplitter() {}

  static int end(String source, int start, int maximum, int requiredEnd) {
    if (maximum >= requiredEnd) return requiredEnd;
    int floor = start + (maximum - start) / 2;
    int heading = -1, paragraph = -1, line = -1, safe = -1;
    char fence = 0;
    int fenceLength = 0;
    // Start at zero so a resumed chunk inside a long fenced block retains fence state.
    for (int position = 0; position < maximum;) {
      int newline = source.indexOf('\n', position);
      int next = newline < 0 ? source.length() : newline + 1;
      String text = source.substring(position, Math.min(next, source.length())).strip();
      int run = 0;
      char marker = text.isEmpty() ? 0 : text.charAt(0);
      if (marker == '`' || marker == '~') {
        while (run < text.length() && text.charAt(run) == marker) run++;
      }
      boolean wasFenced = fence != 0;
      if (!wasFenced && position > start && position <= maximum) safe = position;
      if (run >= 3) {
        if (fence == 0) {
          fence = marker;
          fenceLength = run;
        } else if (marker == fence && run >= fenceLength && text.substring(run).isBlank()) fence = 0;
      }
      if (!wasFenced && fence == 0 && position >= floor && position > start
          && text.matches("#{1,6}\\s+.*")) heading = position;
      if (next <= maximum && next > start) {
        if (fence == 0 && next >= floor) {
          line = next;
          if (text.isBlank() || wasFenced) paragraph = next;
        }
      }
      position = next;
    }
    if (heading > start) return heading;
    if (paragraph > start) return paragraph;
    if (line > start) return line;
    if (safe > start) return safe;
    // An individual block/line larger than the hard budget must still make progress.
    if (maximum > start && maximum < source.length()
        && Character.isHighSurrogate(source.charAt(maximum - 1))
        && Character.isLowSurrogate(source.charAt(maximum))) maximum--;
    return maximum;
  }
}
