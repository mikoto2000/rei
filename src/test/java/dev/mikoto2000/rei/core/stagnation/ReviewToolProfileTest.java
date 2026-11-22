package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

class ReviewToolProfileTest {
  static ToolCallback tool(String name, AtomicInteger executions) {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name(name).description("test").inputSchema("{}").build();
      }

      public String call(String input) {
        executions.incrementAndGet();
        return "ok";
      }
    };
  }

  static Prompt prompt() {
    return new Prompt(
        "review",
        OpenAiChatOptions.builder()
            .toolCallbacks(
                tool("beginReview", new AtomicInteger()),
                    tool("readReviewFile", new AtomicInteger()),
                tool("submitReviewPage", new AtomicInteger()),
                    tool("generalOperation", new AtomicInteger()))
            .toolChoice("auto")
            .temperature(0.0)
            .toolContext(Map.of("authority", "original"))
            .build());
  }

  static List<String> names(Prompt prompt) {
    return ((ToolCallingChatOptions) prompt.getOptions())
        .getToolCallbacks().stream().map(t -> t.getToolDefinition().name()).toList();
  }

  @Test
  void preservesOptionsCallbacksAndIsolationAndValidatesSwitchArguments() {
    var original = prompt();
    var installed = ReviewToolProfile.install(original);
    var other = ReviewToolProfile.install(original);
    var options = (OpenAiChatOptions) installed.getOptions();
    var toggle = options.getToolCallbacks().getLast();
    assertThat(toggle.call("{\"enabled\":true,\"extra\":1}")).contains("false");
    assertThat(ReviewToolProfile.select(installed)).isSameAs(installed);
    toggle.call("{\"enabled\":true}");
    var selected = ReviewToolProfile.select(installed);
    assertThat(names(selected))
        .containsExactly(
            "beginReview", "readReviewFile", "submitReviewPage", ReviewToolProfile.TOOL);
    assertThat(((ToolCallingChatOptions) selected.getOptions()).getToolCallbacks().getFirst())
        .isSameAs(options.getToolCallbacks().getFirst());
    assertThat(((OpenAiChatOptions) selected.getOptions()).getToolChoice()).isEqualTo("auto");
    assertThat(((OpenAiChatOptions) selected.getOptions()).getToolContext())
        .containsEntry("authority", "original");
    assertThat(names(other)).contains("generalOperation");
    assertThat(names(original)).doesNotContain(ReviewToolProfile.TOOL);
    var plain = new Prompt("plain");
    assertThat(ReviewToolProfile.install(plain)).isSameAs(plain);
    assertThat(toggle.call("broken JSON")).contains("false");
    assertThat(names(ReviewToolProfile.select(installed))).doesNotContain("generalOperation");
    toggle.call("{\"enabled\":false}");
    assertThat(ReviewToolProfile.select(installed)).isSameAs(installed);
  }

  @Test
  void controlledLoopRejectsHiddenToolAndRestoresOnlyOriginalAuthority() {
    var executed = new AtomicInteger();
    var budget = new OutputLimitRunBudget(2, 10, null, 0, 2);
    budget.tryConsumeLlmCall();
    var context =
        new RunExecutionContext(
            "profile",
            budget,
            new ProgressEvaluator(Path.of(".")),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    var callbacks =
        new ArrayList<>(((ToolCallingChatOptions) prompt().getOptions()).getToolCallbacks());
    callbacks.set(3, tool("generalOperation", executed));
    var options =
        OpenAiChatOptions.builder()
            .toolCallbacks(callbacks)
            .toolContext(Map.of(RunExecutionContext.KEY, context))
            .build();
    var requests = new ArrayList<Prompt>();
    var delegate = mock(ChatModel.class);
    when(delegate.stream(any(Prompt.class)))
        .thenAnswer(invocation -> {
          Prompt request = invocation.getArgument(0);
          requests.add(request);
          int n = requests.size();
          String name =
              switch (n) {
                case 1, 3 -> ReviewToolProfile.TOOL;
                default -> "generalOperation";
              };
          String input = n == 1 ? "{\"enabled\":true}" : n == 3 ? "{\"enabled\":false}" : "{}";
          var reply =
              n == 5
                  ? new AssistantMessage("done")
                  : AssistantMessage.builder()
                      .toolCalls(
                          List.of(
                              new AssistantMessage.ToolCall("call-" + n, "function", name, input)))
                      .build();
          return Flux.just(new ChatResponse(List.of(new Generation(reply))));
        });
    new StagnationChatModel(delegate).stream(new Prompt("review then repair", options)).blockLast();
    assertThat(requests).hasSize(5);
    assertThat(names(requests.get(1))).doesNotContain("generalOperation");
    assertThat(names(requests.get(2))).doesNotContain("generalOperation");
    assertThat(names(requests.get(3))).contains("generalOperation");
    assertThat(executed).hasValue(1);
    assertThat(
            dev.mikoto2000.rei.core.policy.ToolPermissionPolicy.intrinsicallyReadOnly(
                ReviewToolProfile.TOOL))
        .isTrue();
  }
}
