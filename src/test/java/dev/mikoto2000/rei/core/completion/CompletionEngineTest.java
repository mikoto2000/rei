package dev.mikoto2000.rei.core.completion;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompletionEngineTest {
  static CompletionContext context(String token, String type, Path directory) {
    return new CompletionContext(token, token.length(), List.of(token), 0, 0, token,
        List.of(), Set.of(type), List.of(), directory);
  }
  static CompletionProvider provider(int priority, boolean supports, String... values) {
    return new CompletionProvider() {
      public int priority() { return priority; }
      public boolean supports(CompletionContext context) { return supports; }
      public List<CompletionCandidate> complete(CompletionContext context) {
        return java.util.Arrays.stream(values).map(v -> CompletionCandidate.value(v, "test")).toList();
      }
    };
  }
  @Test void selectsHighestPriorityAndMergesTiesInRegistrationOrder() {
    var engine = new CompletionEngine().register(provider(0, true, "fallback"))
        .register(provider(10, true, "alpha", "shared"))
        .register(provider(10, true, "shared", "beta"))
        .register(provider(20, false, "unsupported"));
    assertThat(engine.complete(context("", "custom", Path.of("."))))
        .extracting(CompletionCandidate::value).containsExactly("alpha", "shared", "beta");
  }
  @Test void emptySpecificProviderSuppressesFallback() {
    assertThat(new CompletionEngine().register(provider(10, true)).register(provider(0, true, "wrong"))
        .complete(context("", "custom", Path.of(".")))).isEmpty();
  }
  @Test void isolatesFailuresDuringSupportsAndCompletion() {
    var engine = new CompletionEngine().register(new CompletionProvider() {
      public boolean supports(CompletionContext c) { throw new IllegalStateException(); }
      public List<CompletionCandidate> complete(CompletionContext c) { throw new AssertionError(); }
    }).register(new CompletionProvider() {
      public boolean supports(CompletionContext c) { return true; }
      public List<CompletionCandidate> complete(CompletionContext c) { throw new IllegalStateException(); }
    }).register(provider(100, true, "ok"));
    assertThat(engine.complete(context("", "custom", Path.of("."))))
        .extracting(CompletionCandidate::value).containsExactly("ok");
  }
}
