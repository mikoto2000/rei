package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.UserMessage;

class SummaryInputPagesTest {
  private final TokenEstimator estimator = TokenEstimator.tokenizerAware();

  @Test
  void nativeVisibleUsageAvoidsRejectingConservativeMultilingualEstimate() {
    String summary = "日本語の確定事項と未解決事項。".repeat(120);
    assertThat(estimator.text(summary)).isGreaterThan(3000);
    var calls = new AtomicInteger();
    var result =
        SummaryInputPages.summarize(
            (previous, messages, hardLimit, target, timeout) -> {
              calls.incrementAndGet();
              assertThat(hardLimit).isEqualTo(3000);
              assertThat(target).isEqualTo(750);
              return new ConversationCompressor.Summary(summary, 1800);
            },
            estimator,
            "",
            List.of(new UserMessage("facts ".repeat(15000))),
            12000,
            24000,
            () -> {},
            Duration.ofSeconds(5));
    assertThat(result.text()).isEqualTo(summary);
    assertThat(calls.get()).isGreaterThan(1);
    assertThat(result.textTokens(estimator)).isEqualTo(1800);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(ints = {0, -1, 4000})
  void oversizeGetsOneTighterCallOnTheSameInputAndDeadline(Integer reported) {
    var calls = new AtomicInteger();
    var seen = new ArrayList<List<org.springframework.ai.chat.messages.Message>>();
    var timeouts = new ArrayList<Duration>();
    String longDraft = "日本語".repeat(700);
    var result =
        SummaryInputPages.summarize(
            (previous, messages, hardLimit, target, timeout) -> {
              seen.add(messages);
              timeouts.add(timeout);
              assertThat(previous).isEmpty();
              assertThat(hardLimit).isEqualTo(3000);
              if (calls.incrementAndGet() == 1) {
                assertThat(target).isEqualTo(750);
                return new ConversationCompressor.Summary(longDraft, reported);
              }
              assertThat(target).isEqualTo(375);
              return new ConversationCompressor.Summary(
                  "decisions; constraints; pending; reference", 2000);
            },
            estimator,
            "original facts",
            List.of(new UserMessage("untrusted source facts")),
            12000,
            24000,
            () -> {},
            Duration.ofSeconds(5));
    assertThat(calls.get()).isEqualTo(2);
    assertThat(seen.get(1)).isEqualTo(seen.getFirst());
    assertThat(seen.get(1).getFirst().getText())
        .contains("original facts", "untrusted source facts")
        .doesNotContain(longDraft);
    assertThat(timeouts.get(1)).isLessThan(timeouts.getFirst());
    assertThat(result.text()).contains("pending", "reference");
  }

  @Test
  void secondOversizeFailsWithoutCroppingOrUnboundedRetries() {
    var calls = new AtomicInteger();
    assertThatThrownBy(() ->
            SummaryInputPages.summarize(
                (previous, messages, hardLimit, target, timeout) -> {
                  calls.incrementAndGet();
                  // Provider usage is authoritative even when a synthetic text estimate is tiny.
                  return new ConversationCompressor.Summary("draft", 4000);
                },
                estimator,
                "",
                List.of(new UserMessage("facts")),
                12000,
                24000,
                () -> {},
                Duration.ofSeconds(5)))
        .isInstanceOfSatisfying(
            ContextCompressionException.class,
            error -> {
              assertThat(error.reason())
                  .isEqualTo(ContextCompressionException.Reason.SUMMARY_TOO_LONG);
              assertThat(error.actualTokens()).isEqualTo(4000);
              assertThat(error.limitTokens()).isEqualTo(3000);
            });
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void cancellationBetweenDraftAndRepairStopsBeforeAnotherModelCall() {
    var calls = new AtomicInteger();
    assertThatThrownBy(() ->
            SummaryInputPages.summarize(
                (previous, messages, hardLimit, target, timeout) -> {
                  calls.incrementAndGet();
                  return new ConversationCompressor.Summary("draft", 4000);
                },
                estimator,
                "",
                List.of(new UserMessage("facts")),
                12000,
                24000,
                () -> {
                  if (calls.get() > 0) throw new CancellationException();
                },
                Duration.ofSeconds(5)))
        .isInstanceOf(CancellationException.class);
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ContextCompressionException.Reason.class,
      names = {"TIMEOUT", "TRANSPORT", "AUTHENTICATION", "EMPTY_SUMMARY"})
  void otherFailuresAreNotRetriedAsOversizedDrafts(ContextCompressionException.Reason reason) {
    var calls = new AtomicInteger();
    var failure = reason == ContextCompressionException.Reason.TIMEOUT
        ? ContextCompressionException.from(new java.net.SocketTimeoutException("private"))
        : new ContextCompressionException(reason, 17, 10);
    assertThatThrownBy(() ->
            SummaryInputPages.summarize(
                (previous, messages, hardLimit, target, timeout) -> {
                  calls.incrementAndGet();
                  throw failure;
                },
                estimator,
                "",
                List.of(new UserMessage("facts")),
                12000,
                24000,
                () -> {},
                Duration.ofSeconds(5)))
        .isInstanceOfSatisfying(ContextCompressionException.class, error -> {
          assertThat(error.reason()).isEqualTo(failure.reason());
          assertThat(error.actualTokens()).isEqualTo(failure.actualTokens());
          assertThat(error.limitTokens()).isEqualTo(failure.limitTokens());
          assertThat(error.details()).startsWith(failure.details())
              .contains("ページ=1", "試行=1", "本文上限=3000", "本文目標=750")
              .doesNotContain("private");
        });
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void outputLimitRetriesOriginalInputWithSmallerGoalAndUnchangedHardLimit() {
    var calls = new AtomicInteger();
    var seen = new ArrayList<List<org.springframework.ai.chat.messages.Message>>();
    var timeouts = new ArrayList<Duration>();
    var result =
        SummaryInputPages.summarize(
            (previous, messages, hardLimit, target, timeout) -> {
              seen.add(messages);
              timeouts.add(timeout);
              assertThat(previous).isEmpty();
              assertThat(hardLimit).isEqualTo(3000);
              if (calls.incrementAndGet() == 1) {
                assertThat(target).isEqualTo(750);
                throw new ContextCompressionException(
                    ContextCompressionException.Reason.OUTPUT_LIMIT);
              }
              assertThat(target).isEqualTo(375);
              // A natural stop between the soft target and hard limit remains valid.
              return new ConversationCompressor.Summary("pending requirement REF-17", 1800);
            },
            estimator,
            "original decisions",
            List.of(new UserMessage("all original untrusted source facts")),
            12000,
            24000,
            () -> {},
            Duration.ofSeconds(5));
    assertThat(calls.get()).isEqualTo(2);
    assertThat(seen.get(1)).isEqualTo(seen.getFirst());
    assertThat(seen.get(1).getFirst().getText())
        .contains("original decisions", "all original untrusted source facts");
    assertThat(timeouts.get(1)).isLessThan(timeouts.getFirst());
    assertThat(result.outputTokens()).isEqualTo(1800);
  }

  @Test
  void repeatedOutputLimitStopsAfterOneRepair() {
    var calls = new AtomicInteger();
    assertThatThrownBy(() ->
            SummaryInputPages.summarize(
                (previous, messages, hardLimit, target, timeout) -> {
                  calls.incrementAndGet();
                  throw new ContextCompressionException(
                      ContextCompressionException.Reason.OUTPUT_LIMIT);
                },
                estimator,
                "",
                List.of(new UserMessage("facts")),
                12000,
                24000,
                () -> {},
                Duration.ofSeconds(5)))
        .isInstanceOfSatisfying(
            ContextCompressionException.class,
            error -> {
              assertThat(error.reason()).isEqualTo(ContextCompressionException.Reason.OUTPUT_LIMIT);
              assertThat(error.details())
                  .contains("ページ=1", "試行=2", "本文上限=3000", "本文目標=375");
            });
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void exhaustedSharedDeadlinePreventsRepairCall() {
    var calls = new AtomicInteger();
    assertThatThrownBy(() ->
            SummaryInputPages.summarize(
                (previous, messages, hardLimit, target, timeout) -> {
                  calls.incrementAndGet();
                  org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> Thread.sleep(100));
                  return new ConversationCompressor.Summary("oversized", 4000);
                },
                TokenEstimator.conservative(),
                "",
                List.of(new UserMessage("facts")),
                12000,
                24000,
                () -> {},
                Duration.ofMillis(50)))
        .isInstanceOfSatisfying(
            ContextCompressionException.class,
            error -> assertThat(error.reason())
                .isEqualTo(ContextCompressionException.Reason.TIMEOUT));
    assertThat(calls.get()).isEqualTo(1);
  }
}
