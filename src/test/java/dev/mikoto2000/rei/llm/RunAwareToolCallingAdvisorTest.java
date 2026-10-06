package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import reactor.core.publisher.Flux;

class RunAwareToolCallingAdvisorTest {
  @ParameterizedTest
  @CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,true,false",
      "false,true,true", "true,true,true"})
  void preservesOneToolOwnerAndNeverExecutesTruncatedRunCalls(boolean stream, boolean hasRun, boolean truncated) {
    var calls = new AtomicInteger();
    var effects = new AtomicInteger();
    var advisorCalls = new AtomicInteger();
    var outerAdvisor = new org.springframework.ai.chat.client.advisor.api.BaseAdvisor() {
      @Override public int getOrder() { return -1000; }
      @Override public String getName() { return "Outer context advisor"; }
      @Override public org.springframework.ai.chat.client.ChatClientRequest before(
          org.springframework.ai.chat.client.ChatClientRequest request,
          org.springframework.ai.chat.client.advisor.api.AdvisorChain chain) {
        advisorCalls.incrementAndGet();
        return request;
      }
      @Override public org.springframework.ai.chat.client.ChatClientResponse after(
          org.springframework.ai.chat.client.ChatClientResponse response,
          org.springframework.ai.chat.client.advisor.api.AdvisorChain chain) { return response; }
    };
    ChatModel delegate = new ChatModel() {
      @Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return OpenAiChatOptions.builder().model("local").build();
      }
      @Override public ChatResponse call(Prompt prompt) { return next(); }
      @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(next())); }
      private ChatResponse next() {
        if (calls.incrementAndGet() > 1) {
          return new ChatResponse(List.of(new Generation(new AssistantMessage("done"),
              ChatGenerationMetadata.builder().finishReason("stop").build())));
        }
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "readFile", "{}"))).build(),
            ChatGenerationMetadata.builder().finishReason(truncated ? "length" : "tool_calls").build())));
      }
    };
    var callback = new ToolCallback() {
      @Override public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name("readFile").description("Read test content")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
      }
      @Override public String call(String input) { effects.incrementAndGet(); return "test content"; }
    };
    var options = OpenAiChatOptions.builder().model("local");
    if (hasRun) {
      var context = new RunExecutionContext("test-run", new OutputLimitRunBudget(1, 5),
          new ProgressEvaluator(Path.of(".")), new AgentEventFactory(Clock.systemUTC()), event -> {});
      options.toolContext(Map.of(RunExecutionContext.KEY, context));
    }
    var client = ChatClient.builder(new StagnationChatModel(delegate))
        .defaultAdvisors(outerAdvisor, new RunAwareToolCallingAdvisor()).defaultOptions(options).defaultToolCallbacks(callback).build();

    if (stream) client.prompt().user("test").stream().chatResponse().blockLast();
    else client.prompt().user("test").call().chatResponse();

    assertThat(calls).hasValue(truncated ? 1 : 2);
    assertThat(effects).hasValue(truncated ? 0 : 1);
    assertThat(advisorCalls).hasValue(1);
  }
}
