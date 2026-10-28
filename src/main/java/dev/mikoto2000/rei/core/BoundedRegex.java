package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.tooldelivery.ReadingProblem;

import java.util.regex.Pattern;

/**
 * Deterministic character-access budget around Java regex, retaining lookarounds/backreferences.
 */
final class BoundedRegex {
  private BoundedRegex() {}

  static boolean find(Pattern pattern, String text) {
    return find(pattern, text, 4_000_000);
  }

  static boolean find(Pattern pattern, String text, int steps) {
    if (steps < 1) throw new IllegalArgumentException("Regex steps must be positive");
    if ((pattern.flags() & Pattern.LITERAL) != 0) return pattern.matcher(text).find();
    try {
      return pattern.matcher(new Input(text, 0, text.length(), new Budget(steps))).find();
    } catch (StackOverflowError exhausted) {
      throw ReadingProblem.fail("SEARCH_PATTERN_BUDGET");
    }
  }

  private static final class Budget {
    private int remaining;

    Budget(int remaining) {
      this.remaining = remaining;
    }

    void spend(int count) {
      if (count > remaining) throw ReadingProblem.fail("SEARCH_PATTERN_BUDGET");
      remaining -= count;
      if ((remaining & 1023) == 0 && Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException();
    }
  }

  private record Input(String text, int start, int length, Budget budget) implements CharSequence {
    @Override
    public char charAt(int index) {
      java.util.Objects.checkIndex(index, length);
      budget.spend(1);
      return text.charAt(start + index);
    }

    @Override
    public CharSequence subSequence(int from, int to) {
      java.util.Objects.checkFromToIndex(from, to, length);
      return new Input(text, start + from, to - from, budget);
    }

    @Override
    public String toString() {
      budget.spend(length);
      return text.substring(start, start + length);
    }
  }
}
