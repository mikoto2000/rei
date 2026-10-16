package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ContextCompressionPayload;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

@Tag("integration")
class ContextCompressionDiagnosticsTest {
  @TempDir Path dir;
  private final TokenEstimator tokens = TokenEstimator.conservative();
  private final AtomicLong millis = new AtomicLong();
  private final List<AgentEvent> events = new ArrayList<>();
  private final ContextCompressionProperties properties = config();

  private static ContextCompressionProperties config() {
    var p = new ContextCompressionProperties();
    p.setThreshold(300);
    p.setHardLimit(3000);
    p.setRecentTokens(80);
    p.setSummaryTokens(100);
    return p;
  }

  private Clock clock() {
    return new Clock() {
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      public Clock withZone(ZoneId zone) {
        return this;
      }

      public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
      }
    };
  }

  private ContextAssembler assembler(ConversationCompressor compressor) {
    return assembler(compressor, new ConversationSummaryRepository(dir));
  }

  private ContextAssembler assembler(
      ConversationCompressor compressor, ConversationSummaryRepository summaries) {
    var a =
        new ContextAssembler(
            properties,
            tokens,
            summaries,
            new ToolResultCompressor(new RawToolResultStore(dir), tokens, 100, 70),
            compressor,
            new AgentEventFactory(clock()),
            events::add);
    a.setCompressionRetryClock(clock());
    return a;
  }

  private Prompt history() {
    return new Prompt(
        List.of(
            new SystemMessage("system"),
            ContextHistoryAdvisor.historical(new UserMessage("old ".repeat(250)), 1),
            ContextHistoryAdvisor.historical(new AssistantMessage("other ".repeat(400)), 2),
            ContextHistoryAdvisor.historical(new AssistantMessage("recent"), 3),
            new UserMessage("current")));
  }

  private List<ContextCompressionPayload> payloads(AgentEventType type) {
    return events.stream()
        .filter(e -> e.type() == type)
        .map(e -> (ContextCompressionPayload) e.payload())
        .toList();
  }

  @Test
  void timeoutIsNotRetriedOnEveryLoopAndNextAttemptUsesSmallerCompletePrefix() {
    List<Integer> sizes = new ArrayList<>();
    var a =
        assembler(
            (previous, messages, budget, owner) -> {
              sizes.add(messages.size());
              throw new ContextCompressionException(ContextCompressionException.Reason.TIMEOUT);
            });
    var original = history();
    for (int i = 0; i < 5; i++)
      assertThat(a.assemble(original, "chat", "run", () -> {}).getUserMessage().getText())
          .isEqualTo("current");
    assertThat(sizes).containsExactly(2);
    assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_FAILED))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.reason()).isEqualTo("summary_timeout");
              assertThat(p.details()).contains("summary-timeout-seconds", "120", "小さく");
            });
    assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_SKIPPED)).hasSize(1);
    assertThat(new ConversationSummaryRepository(dir).read("chat").throughSequence()).isZero();
    millis.set(120000);
    a.assemble(original, "chat", "run", () -> {});
    assertThat(sizes).containsExactly(2, 1);
    assertThat(original.getContents()).contains("old old", "other other");
    assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_FAILED).getLast().details())
        .contains("240");
    assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_FAILED).getLast())
        .satisfies(p -> assertThat(p.afterEstimatedTokens()).isEqualTo(p.beforeEstimatedTokens()));
  }

  @Test
  void newRunOrModelOrChangedSettingsCanRetryWithoutWaiting() {
    var calls = new AtomicInteger();
    var a =
        assembler(
            (previous, messages, budget, owner) -> {
              calls.incrementAndGet();
              throw new IllegalStateException("raw-secret");
            });
    a.assemble(history(), "chat", "run", () -> {});
    a.assemble(history(), "chat", "other-run", () -> {});
    var changed =
        new Prompt(
            history().getInstructions(),
            ToolCallingChatOptions.builder().model("another-model").build());
    a.assemble(changed, "chat", "other-run", () -> {});
    properties.setSummaryTokens(110);
    a.assemble(changed, "chat", "other-run", () -> {});
    assertThat(calls).hasValue(4);
    assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_FAILED))
        .allSatisfy(p -> assertThat(p.details()).doesNotContain("raw-secret"));
  }

  @Test
  void reportsOutputValidationSeparatelyWithoutDiscardingExistingSummary() {
    for (var reason :
        List.of(
            ContextCompressionException.Reason.EMPTY_SUMMARY,
            ContextCompressionException.Reason.SUMMARY_TOO_LONG,
            ContextCompressionException.Reason.INSUFFICIENT_GAIN)) {
      events.clear();
      var summaries = new ConversationSummaryRepository(dir.resolve(reason.name()));
      summaries.save("chat", new ConversationSummary("saved decision", 1, Instant.now()));
      properties.setSummaryTokens(
          reason == ContextCompressionException.Reason.INSUFFICIENT_GAIN ? 1000 : 100);
      var a =
          assembler(
              (previous, messages, budget, owner) ->
                  switch (reason) {
                    case EMPTY_SUMMARY -> "";
                    case SUMMARY_TOO_LONG -> "あ".repeat(60);
                    default -> messages.getFirst().getText();
                  },
              summaries);
      a.assemble(history(), "chat", reason.name(), () -> {});
      assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_FAILED))
          .singleElement()
          .satisfies(p -> assertThat(p.reason()).isEqualTo(reason.code()));
      assertThat(summaries.read("chat").summary()).isEqualTo("saved decision");
      assertThat(summaries.read("chat").throughSequence()).isEqualTo(1);
    }
  }

  @Test
  void actualOutputUsageAvoidsRejectingJapaneseSummaryButFinalInputBudgetRemainsStrict() {
    ConversationCompressor c =
        new ConversationCompressor() {
          public String summarize(String previous, List<Message> messages, int max, Prompt owner) {
            throw new AssertionError();
          }

          public Summary summarizeResult(
              String previous, List<Message> messages, int max, Prompt owner) {
            return new Summary("あ".repeat(60), 80);
          }
        };
    var a = assembler(c);
    var original = history();
    var result = a.assemble(original, "chat", "run", () -> {});
    assertThat(tokens.text("あ".repeat(60))).isGreaterThan(properties.getSummaryTokens());
    assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_COMPLETED)).hasSize(1);
    assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_FAILED)).isEmpty();
    assertThat(result.getContents()).contains("あ".repeat(60));
    assertThat(result.getInstructions().stream().mapToLong(tokens::message).sum())
        .isLessThanOrEqualTo(properties.getHardLimit());
    properties.setHardLimit(100);
    assertThatThrownBy(() -> a.assemble(original, "chat", "run", () -> {}))
        .hasMessageContaining("CONTEXT_HARD_LIMIT");
  }

  @Test
  void unknownOrZeroUsageStillUsesConservativeEstimateForSummaryCap() {
    for (Integer usage : Arrays.asList(null, 0)) {
      events.clear();
      ConversationCompressor c =
          new ConversationCompressor() {
            public String summarize(
                String previous, List<Message> messages, int max, Prompt owner) {
              throw new AssertionError();
            }

            public Summary summarizeResult(
                String previous, List<Message> messages, int max, Prompt owner) {
              return new Summary("あ".repeat(60), usage);
            }
          };
      assembler(c).assemble(history(), "chat", String.valueOf(usage), () -> {});
      assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_FAILED))
          .singleElement()
          .satisfies(
              p -> {
                assertThat(p.reason()).isEqualTo("summary_too_long");
                assertThat(p.details()).contains("120", "100");
              });
    }
  }

  @Test
  void reportsSummaryStorageAndOversizedAtomicInputBeforeSendingToModel() {
    var summaries =
        new ConversationSummaryRepository(dir) {
          @Override
          public void save(String key, ConversationSummary summary) {
            throw new IllegalStateException(
                "storage secret", new java.nio.file.AccessDeniedException("private-storage-path"));
          }
        };
    assembler((p, m, b, o) -> "compact", summaries).assemble(history(), "chat", "run", () -> {});
    assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_FAILED))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.reason()).isEqualTo("summary_storage");
              assertThat(p.details())
                  .contains("storage_access_denied")
                  .doesNotContain("storage secret", "private-storage-path");
            });
    events.clear();
    properties.setModelContextLimit(5000);
    properties.setCompletionReserve(0);
    properties.setToolReserve(0);
    properties.setSafetyMargin(0);
    var calls = new AtomicInteger();
    var original =
        new Prompt(
            List.of(
                ContextHistoryAdvisor.historical(new UserMessage("x".repeat(20000)), 1),
                ContextHistoryAdvisor.historical(new AssistantMessage("recent"), 2),
                new UserMessage("current")));
    assembler(
            (p, m, b, o) -> {
              calls.incrementAndGet();
              return "compact";
            })
        .assemble(original, "chat", "oversized", () -> {});
    assertThat(calls).hasValue(0);
    assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_FAILED))
        .singleElement()
        .satisfies(p -> assertThat(p.reason()).isEqualTo("summary_input_budget"));
  }

  @Test
  void retainsHttpStatusAndSafeUnderlyingCauseWithoutProviderBodyOrHeaders() {
    var failure =
        org.springframework.web.reactive.function.client.WebClientResponseException.create(
            401,
            "secret-status",
            org.springframework.http.HttpHeaders.EMPTY,
            "secret-body".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            java.nio.charset.StandardCharsets.UTF_8);
    assembler(
            (p, m, b, o) -> {
              throw new RuntimeException("secret-wrapper", failure);
            })
        .assemble(history(), "chat", "run", () -> {});
    assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_FAILED))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.reason()).isEqualTo("summary_authentication");
              assertThat(p.details())
                  .contains("authentication_failed", "HTTP 401")
                  .doesNotContain("secret-status", "secret-body", "secret-wrapper");
            });
  }

  @Test
  void budgetAndCancellationStayFatalAndReportOriginalTimeoutSafely() {
    var stopped =
        new ExecutionStoppedException(ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN);
    stopped.addSuppressed(
        new ContextCompressionException(ContextCompressionException.Reason.TIMEOUT));
    assertThatThrownBy(
            () ->
                assembler(
                        (p, m, b, o) -> {
                          throw new RuntimeException("secret", stopped);
                        })
                    .assemble(history(), "chat", "run", () -> {}))
        .isSameAs(stopped);
    assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_FAILED))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.reason()).isEqualTo("token_usage_unknown");
              assertThat(p.details()).contains("summary_timeout").doesNotContain("secret");
            });
    events.clear();
    assertThatThrownBy(
            () ->
                assembler(
                        (p, m, b, o) -> {
                          throw new java.util.concurrent.CancellationException("secret");
                        })
                    .assemble(history(), "chat", "cancelled", () -> {}))
        .isInstanceOf(java.util.concurrent.CancellationException.class);
    assertThat(payloads(AgentEventType.CONTEXT_COMPRESSION_FAILED))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.reason()).isEqualTo("cancelled");
              assertThat(p.details()).doesNotContain("secret");
            });
  }
}
