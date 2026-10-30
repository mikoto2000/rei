package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

@Tag("integration")
class ContextTrimmingCostTest {
  @TempDir Path root;

  private ContextAssembler assembler(TokenEstimator estimator, int hard) {
    var settings = new ContextCompressionProperties();
    settings.setHardLimit(hard);
    settings.setThreshold(hard - 1);
    settings.setRecentTokens(Integer.MAX_VALUE);
    return new ContextAssembler(
        settings,
        estimator,
        new ConversationSummaryRepository(root),
        new ToolResultCompressor(new RawToolResultStore(root), estimator, 100, 70),
        (previous, messages, budget, request) -> {
          throw new AssertionError("No summarization needed");
        },
        null,
        null);
  }

  @Test
  void trimmingHundredsOfGroupsKeepsExactTailWithLinearEstimationWork() {
    var delegate = TokenEstimator.conservative();
    var calls = new AtomicInteger();
    TokenEstimator measured =
        new TokenEstimator() {
          @Override
          public int text(String text) {
            return delegate.text(text);
          }

          @Override
          public int message(Message message) {
            calls.incrementAndGet();
            return delegate.message(message);
          }
        };
    var history = new ArrayList<Message>();
    for (int i = 0; i < 200; i++) {
      history.add(
          ContextHistoryAdvisor.historical(
              new AssistantMessage("answer-" + i + "x".repeat(80)), i * 2L + 1));
      history.add(
          ToolResponseMessage.builder()
              .responses(
                  List.of(
                      new ToolResponseMessage.ToolResponse(
                          "call-" + i, "lookup", "result-" + i + "y".repeat(80))))
              .metadata(Map.of(ContextHistoryAdvisor.SEQUENCE, i * 2L + 2))
              .build());
    }
    var system = new SystemMessage("policy");
    var user = new UserMessage("current");
    var messages = new ArrayList<Message>();
    messages.add(system);
    messages.addAll(history);
    messages.add(user);
    long total = ContextBudgetManager.estimateMessages(messages, delegate);
    int from = 0;
    while (total > 600 && from < history.size() - 1) {
      int end = from + 1;
      while (end < history.size() && history.get(end) instanceof ToolResponseMessage) end++;
      if (end == history.size()) break;
      for (int i = from; i < end; i++) total -= delegate.message(history.get(i));
      from = end;
    }
    var expected = new ArrayList<Message>();
    expected.add(system);
    expected.addAll(history.subList(from, history.size()));
    expected.add(user);
    var original = new Prompt(messages);
    var result = assembler(measured, 600).assemble(original, "chat", "run", () -> {});
    assertThat(result.getInstructions()).containsExactlyElementsOf(expected);
    assertThat(original.getInstructions()).hasSize(402);
    assertThat(calls.get()).isLessThan(12 * messages.size());
    System.out.println("Context trimming: 200 groups, message estimates=" + calls.get());
  }

  @Test
  void duplicateSequenceKeysAreRemovedOnlyAfterTheirLastEntry() {
    var first = ContextHistoryAdvisor.historical(new AssistantMessage("superseded"), 1);
    var duplicate = ContextHistoryAdvisor.historical(new AssistantMessage("B".repeat(80)), 1);
    var last = ContextHistoryAdvisor.historical(new AssistantMessage("C"), 2);
    var system = new SystemMessage("s");
    var user = new UserMessage("u");
    var result =
        assembler(TokenEstimator.conservative(), 60)
            .assemble(
                new Prompt(List.of(system, first, duplicate, last, user)), "chat", "run", () -> {});
    assertThat(result.getInstructions()).containsExactly(system, last, user);
  }
}
