package dev.mikoto2000.rei.tooldelivery;

import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** The host binds scope and estimation; reading state never reaches into the run engine. */
public final class ReadingRuntime {
  private static volatile Supplier<ReadingRun> scope = () -> null;
  private static volatile ToIntFunction<String> estimator;

  private ReadingRuntime() {}

  public static void configure(Supplier<ReadingRun> owner, ToIntFunction<String> tokens) {
    scope = java.util.Objects.requireNonNull(owner);
    estimator = java.util.Objects.requireNonNull(tokens);
  }

  public static ReadingRun current() {
    return scope.get();
  }

  public static int estimateTokens(String text) {
    return estimator == null ? 0 : estimator.applyAsInt(text);
  }
}
