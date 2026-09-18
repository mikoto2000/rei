package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.*;
import reactor.core.publisher.Flux;

class ContextCancellationTest {
  @TempDir Path dir;
  @Test void disposingParentCancelsInFlightSummaryHttpStream() throws Exception {
    var started = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    var summaryModel = mock(ChatModel.class);
    when(summaryModel.stream(any(Prompt.class))).thenReturn(Flux.<ChatResponse>never()
        .doOnSubscribe(s -> started.countDown()).doOnCancel(stopped::countDown));
    var models = mock(LlmModelProvider.class);
    when(models.chatModel(LlmFeature.CHAT)).thenReturn(summaryModel);
    when(models.chatOptions(eq(LlmFeature.CHAT), any())).thenReturn(OpenAiChatOptions.builder().build());
    @SuppressWarnings("unchecked") var provider = (ObjectProvider<LlmModelProvider>) mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(models);
    var props = new ContextCompressionProperties();
    props.setThreshold(300); props.setHardLimit(600); props.setRecentTokens(80); props.setSummaryTokens(100);
    var summaries = new ConversationSummaryRepository(dir);
    var tokens = TokenEstimator.conservative();
    var events = new AgentEventFactory(Clock.systemUTC());
    var context = new RunExecutionContext("run", new OutputLimitRunBudget(2, 10), mock(ProgressEvaluator.class), events, e -> {});
    context.setRunContext(new AgentRunContext("run", "chat", dir));
    var assembler = new ContextAssembler(props, tokens, summaries,
        new ToolResultCompressor(new RawToolResultStore(dir), tokens, 100, 70),
        new LlmConversationCompressor(provider, props), events, e -> {});
    var nextLlmCalls = new AtomicInteger();
    var nextModel = mock(ChatModel.class);
    when(nextModel.stream(any(Prompt.class))).thenAnswer(i -> { nextLlmCalls.incrementAndGet(); return Flux.empty(); });
    var options = OpenAiChatOptions.builder().toolContext(Map.of(RunExecutionContext.KEY, context)).build();
    var prompt = new Prompt(List.of(ContextHistoryAdvisor.historical(new UserMessage("old ".repeat(500)), 1),
        ContextHistoryAdvisor.historical(new AssistantMessage("recent"), 2), new UserMessage("current")), options);
    var subscription = new StagnationChatModel(nextModel, assembler).stream(prompt).subscribe(r -> {}, error -> {});
    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    context.cancel();
    subscription.dispose();
    assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(nextLlmCalls).hasValue(0);
    assertThat(summaries.read("chat").throughSequence()).isZero();
  }
}
