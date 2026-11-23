package dev.mikoto2000.rei.llm.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Flux;

class ToolDiscoveryLoopTest {
  @Test
  void disablingDiscoveryDoesNotAllowAnOversizedFallbackRequest() throws Exception {
    var disabled =
        new ToolDiscovery(
            new ToolDiscoveryProperties(),
            new DefaultListableBeanFactory().getBeanProvider(ToolDiscoveryStore.class));
    var initial = prompt(execution("disabled"));
    assertThatThrownBy(() -> disabled.validateOutbound(initial))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("184");
    var unowned =
        new Prompt(
            initial.getInstructions(),
            ((OpenAiChatOptions) initial.getOptions()).mutate().toolContext(Map.of()).build());
    assertThatThrownBy(() ->
            new StagnationChatModel(mock(ChatModel.class))
                .withToolDiscovery(disabled)
                .call(unowned))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("184");
  }

  static RunExecutionContext execution(String id) {
    var budget = new OutputLimitRunBudget(3, 10);
    budget.tryConsumeLlmCall();
    var c =
        new RunExecutionContext(
            id,
            budget,
            new ProgressEvaluator(Path.of(".")),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    c.setRunContext(
        new AgentRunContext(
            id,
            "session",
            Path.of("."),
            "project",
            AgentRunContext.RequestSource.SHELL,
            AgentRunContext.Mode.EXCLUSIVE));
    return c;
  }

  static ToolDiscovery discovery() {
    var p = new ToolDiscoveryProperties();
    p.setEnabled(true);
    return new ToolDiscovery(
        p, new DefaultListableBeanFactory().getBeanProvider(ToolDiscoveryStore.class));
  }

  static Prompt prompt(RunExecutionContext context) throws Exception {
    return new Prompt(
        "明日の予定を確認",
        OpenAiChatOptions.builder()
            .model("test-model")
            .toolCallbacks(ToolCatalogTest.all())
            .toolContext(Map.of(RunExecutionContext.KEY, context))
            .build());
  }

  @Test
  void publishes32Then33InTheRealRunLoopAndKeepsTheOldSnapshot() throws Exception {
    var seen = new ArrayList<Prompt>();
    var turn = new AtomicInteger();
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class)))
        .thenAnswer(invocation -> {
          Prompt p = invocation.getArgument(0);
          seen.add(p);
          if (turn.getAndIncrement() == 0)
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            AssistantMessage.builder()
                                .content("")
                                .toolCalls(
                                    List.of(
                                        new AssistantMessage.ToolCall(
                                            "find",
                                            "function",
                                            "searchTools",
                                            "{\"query\":\"googleCalendarListEventsForDate\",\"maxResults\":1}")))
                                .build()))));
          return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
        });
    new StagnationChatModel(model)
        .withToolDiscovery(discovery()).stream(prompt(execution("run"))).blockLast();
    assertThat(seen).hasSize(2);
    assertThat(((ToolCallingChatOptions) seen.get(0).getOptions()).getToolCallbacks()).hasSize(32);
    assertThat(((ToolCallingChatOptions) seen.get(1).getOptions()).getToolCallbacks())
        .hasSize(33)
        .anyMatch(c -> c.getToolDefinition().name().equals("googleCalendarListEventsForDate"));
    assertThat(((ToolCallingChatOptions) seen.get(0).getOptions()).getToolCallbacks())
        .noneMatch(c -> c.getToolDefinition().name().equals("googleCalendarListEventsForDate"));
  }

  @Test
  void rejectsAnUnpublishedSiblingBeforeSearchRuns() throws Exception {
    var seen = new ArrayList<Prompt>();
    var turn = new AtomicInteger();
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class)))
        .thenAnswer(invocation -> {
          Prompt p = invocation.getArgument(0);
          seen.add(p);
          if (turn.getAndIncrement() == 0)
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            AssistantMessage.builder()
                                .content("")
                                .toolCalls(
                                    List.of(
                                        new AssistantMessage.ToolCall(
                                            "find",
                                            "function",
                                            "searchTools",
                                            "{\"query\":\"googleCalendarListEventsForDate\"}"),
                                        new AssistantMessage.ToolCall(
                                            "bad",
                                            "function",
                                            "googleCalendarListEventsForDate",
                                            "{}")))
                                .build()))));
          return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
        });
    new StagnationChatModel(model)
        .withToolDiscovery(discovery()).stream(prompt(execution("run"))).blockLast();
    assertThat(seen).hasSize(2);
    assertThat(((ToolCallingChatOptions) seen.get(1).getOptions()).getToolCallbacks()).hasSize(32);
  }

  @Test
  void readOnlyDiscoveryDoesNotExposeWriteToolsEvenWithAmbientDefaults() throws Exception {
    var run = execution("readonly");
    run.setRunContext(
        new AgentRunContext(
            "readonly",
            "session",
            Path.of("."),
            "project",
            AgentRunContext.RequestSource.SHELL,
            AgentRunContext.Mode.READ_ONLY));
    var initial = prompt(run);
    var session = discovery().start(initial, run, c -> c);
    var projected = session.project(initial);
    var callbacks = ((ToolCallingChatOptions) projected.getOptions()).getToolCallbacks();
    assertThat(callbacks).noneMatch(c -> c.getToolDefinition().name().equals("writeMultiFile"));
    var search =
        callbacks.stream()
            .filter(c -> c.getToolDefinition().name().equals("searchTools"))
            .findFirst()
            .orElseThrow();
    assertThat(search.call("{\"query\":\"googleCalendarCreateEvent\",\"maxResults\":1}"))
        .contains("no_match");
  }

  @Test
  void pendingCallsUseTheOriginalRequestEvenAfterSearchChangesNextVisibility() throws Exception {
    var run = execution("snapshot");
    var initial = prompt(run);
    var session = discovery().start(initial, run, c -> c);
    var first = session.project(initial);
    ((ToolCallingChatOptions) first.getOptions())
        .getToolCallbacks().stream()
            .filter(c -> c.getToolDefinition().name().equals("searchTools"))
            .findFirst()
            .orElseThrow()
            .call("{\"query\":\"googleCalendarListEventsForDate\",\"maxResults\":1}");
    assertThatThrownBy(() -> session.validatePending(List.of("googleCalendarListEventsForDate")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not published");
    session.project(initial);
    session.validatePending(List.of("googleCalendarListEventsForDate"));
  }

  @Test
  void reviewVisibilityCannotPublishNonReviewOperationsOrRestoreAll184() throws Exception {
    var run = execution("review-profile");
    var template = prompt(run);
    var options = (OpenAiChatOptions) template.getOptions();
    var initial =
        dev.mikoto2000.rei.core.stagnation.ReviewToolProfile.install(
            new Prompt(
                template.getInstructions(),
                options
                    .mutate()
                    .toolCallbacks(
                        options.getToolCallbacks().stream()
                            .filter(
                                c -> !c.getToolDefinition().name().equals("setReviewToolProfile"))
                            .toList())
                    .build()));
    var session = discovery().start(initial, run, c -> c);
    run.setToolDiscovery(session);
    var first = session.project(initial);
    var search =
        ((ToolCallingChatOptions) first.getOptions())
            .getToolCallbacks().stream()
                .filter(c -> c.getToolDefinition().name().equals("searchTools"))
                .findFirst()
                .orElseThrow();
    search.call("{\"query\":\"setReviewToolProfile\",\"maxResults\":1}");
    var second = session.project(initial);
    var toggle =
        ((ToolCallingChatOptions) second.getOptions())
            .getToolCallbacks().stream()
                .filter(c -> c.getToolDefinition().name().equals("setReviewToolProfile"))
                .findFirst()
                .orElseThrow();
    toggle.call("{\"enabled\":true}");
    session.project(dev.mikoto2000.rei.core.stagnation.ReviewToolProfile.select(initial));
    assertThat(search.call("{\"query\":\"googleCalendarCreateEvent\",\"maxResults\":1}"))
        .contains("unavailable");
    toggle.call("{\"enabled\":false}");
    var restored =
        session.project(dev.mikoto2000.rei.core.stagnation.ReviewToolProfile.select(initial));
    assertThat(((ToolCallingChatOptions) restored.getOptions()).getToolCallbacks()).hasSize(33);
  }
}
