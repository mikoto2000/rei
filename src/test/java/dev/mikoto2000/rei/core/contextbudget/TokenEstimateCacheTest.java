package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TokenEstimateCacheTest {
  @Test
  void unchangedContextTextIsEstimatedOnceAndChangedTextIsRecounted() {
    var calls = new AtomicInteger();
    var cached =
        TokenEstimator.boundedCache(
            text -> {
              calls.incrementAndGet();
              return text.length();
            });
    for (int iteration = 0; iteration < 100; iteration++)
      assertThat(cached.text("unchanged tool schema")).isEqualTo(21);
    assertThat(calls).hasValue(1);
    assertThat(cached.text("changed schema")).isEqualTo(14);
    assertThat(calls).hasValue(2);
  }

  @Test
  void leastRecentlyUsedEntriesAreEvictedAndHotEntriesRemain() {
    var calls = new AtomicInteger();
    var cached =
        TokenEstimator.boundedCache(
            text -> {
              calls.incrementAndGet();
              return text.length();
            });
    cached.text("hot");
    for (int index = 0; index < 1023; index++) cached.text("key-" + index);
    cached.text("hot");
    cached.text("extra");
    cached.text("hot");
    assertThat(calls).hasValue(1025);
    cached.text("key-0");
    assertThat(calls).hasValue(1026);
  }

  @Test
  void characterCapacityEvictsEntriesAndGiantTextBypassesRetention() {
    var calls = new AtomicInteger();
    var cached =
        TokenEstimator.boundedCache(
            text -> {
              calls.incrementAndGet();
              return text == null ? 0 : text.length();
            });
    String first = "0" + "x".repeat(32000);
    for (int index = 0; index < 35; index++) cached.text(index + "x".repeat(32000));
    cached.text(first);
    assertThat(calls).hasValue(36);
    String giant = "x".repeat(32769);
    cached.text(giant);
    cached.text(giant);
    assertThat(calls).hasValue(38);
    assertThat(cached.text(null)).isZero();
  }

  @Test
  void failedEstimatesAreNotCached() {
    var calls = new AtomicInteger();
    var cached =
        TokenEstimator.boundedCache(
            text -> {
              if (calls.incrementAndGet() == 1) throw new IllegalStateException("retryable");
              return 7;
            });
    assertThatThrownBy(() -> cached.text("same")).isInstanceOf(IllegalStateException.class);
    assertThat(cached.text("same")).isEqualTo(7);
    assertThat(calls).hasValue(2);
  }

  @Test
  void cachedCountsMatchTokenizerCountsForUnicodeAndWireEscaping() {
    var original = TokenEstimator.tokenizerAware();
    var cached = TokenEstimator.boundedCache(original);
    for (String text :
        List.of("", "日本語😀", "\"quote\"\\path\n\t", "{\"tools\":[\"read\"]}", "x".repeat(33000))) {
      assertThat(cached.text(text)).isEqualTo(original.text(text));
      assertThat(cached.text(text)).isEqualTo(original.text(text));
    }
  }

  @Test
  void concurrentContextEstimatesRemainCorrect() throws Exception {
    var cached = TokenEstimator.boundedCache(text -> text.codePointCount(0, text.length()));
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int worker = 0; worker < 4; worker++)
        futures.add(
            executor.submit(
                () -> {
                  for (int index = 0; index < 1500; index++) {
                    String text = "文😀" + index;
                    assertThat(cached.text(text)).isEqualTo(text.codePointCount(0, text.length()));
                  }
                }));
      for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
  }
}
