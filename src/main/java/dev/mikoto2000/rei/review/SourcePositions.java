package dev.mikoto2000.rei.review;

import java.util.Arrays;

/** UTF-16 line starts for one immutable decoded source, local to a review operation. */
final class SourcePositions {
  private final int[] starts;

  SourcePositions(String source) {
    int lines = 1;
    for (int offset = source.indexOf('\n'); offset >= 0; offset = source.indexOf('\n', offset + 1))
      lines++;
    starts = new int[lines];
    int line = 1;
    for (int offset = source.indexOf('\n'); offset >= 0; offset = source.indexOf('\n', offset + 1))
      starts[line++] = offset + 1;
  }

  int[] at(int offset) {
    int line = Arrays.binarySearch(starts, offset);
    if (line < 0) line = -line - 2;
    return new int[] {line + 1, offset - starts[line]};
  }
}
