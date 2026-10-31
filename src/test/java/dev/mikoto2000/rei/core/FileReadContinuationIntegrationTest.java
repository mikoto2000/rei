package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import dev.mikoto2000.rei.core.contextbudget.ResultDelivery;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ToolCompletedPayload;
import dev.mikoto2000.rei.event.ToolEventCallbackDecorator;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;

class FileReadContinuationIntegrationTest {
  @TempDir Path root;
  private final ObjectMapper json = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings = {"execute", "resumable", "pending"})
  void continuesRealPartialLineWithoutRepeatingSourceAndRejectsChangedVersion(String route)
      throws Exception {
    String source =
        java.util.stream.IntStream.range(0, 400)
            .mapToObj(i -> i + ":教材🙂;")
            .collect(java.util.stream.Collectors.joining());
    Files.writeString(root.resolve("a.md"), source);
    var tools = new Tools();
    var properties = new ToolResultDeliveryProperties();
    properties.setPageTargetTokens(512);
    properties.setPageMaxTokens(1024);
    tools.configureDelivery(TokenEstimator.conservative(), properties);
    var inputs = new ArrayList<String>();
    var summaries = new ArrayList<String>();
    var nativeRead =
        new DeliveryToolRegistration.Registered() {
          @Override
          public ResultDelivery.Kind deliveryKind() {
            return ResultDelivery.Kind.FILE_CONTENT;
          }

          @Override
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("readMultiFile")
                .description("read")
                .inputSchema(
                    "{\"type\":\"object\",\"required\":[\"files\"],\"properties\":{\"files\":{\"type\":\"array\"}}}")
                .build();
          }

          @Override
          public String call(String input) {
            try {
              inputs.add(input);
              var files =
                  json.convertValue(
                      json.readTree(input).path("files"),
                      new TypeReference<List<Tools.ReadFileRequest>>() {});
              return json.writeValueAsString(tools.readMultiFile(files, root));
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
        };
    var events = new AgentEventFactory(Clock.systemUTC());
    var callback =
        new ToolEventCallbackDecorator(
            nativeRead,
            events,
            event -> {
              if (event.payload() instanceof ToolCompletedPayload completed)
                summaries.add(completed.resultSummary());
            });
    var options = OpenAiChatOptions.builder().toolCallbacks(callback).build();
    var owner = new AgentRunContext("read-run", "read-session", root, "read-project");
    var context =
        new RunExecutionContext(
            "read-run",
            new OutputLimitRunBudget(2, 50, null),
            new ProgressEvaluator(root, null),
            events,
            event -> {});
    context.setRunContext(owner);
    var loop = new ToolLoopSupport();
    try (var scope = AgentRunScope.open(owner)) {
      var first =
          loop.execute(
              new Prompt("review", options),
              response(
                  call("first", "{\"files\":[{\"path\":\"a.md\",\"purpose\":\"TARGETED\"}]}")));
      var page = page(first);
      assertThat(page.path("truncated").asBoolean()).isTrue();
      var cursor = page.path("nextRead");
      assertThat(cursor.path("offset").asInt()).isPositive();
      String args =
          "{\"files\":[{\"path\":\"a.md\",\"startLine\":"
              + cursor.path("startLine").asInt()
              + "}]}";
      var prompt = new Prompt(first.conversationHistory(), options);
      var next = execute(route, loop, prompt, call("next", args), context);
      var effective = json.readTree(inputs.getLast()).path("files").get(0);
      assertThat(effective.path("offset")).isEqualTo(cursor.path("offset"));
      assertThat(effective.path("expectedVersion")).isEqualTo(cursor.path("expectedVersion"));
      var nextPage = page(next);
      var prefix = page.path("content").get(0).asText() + nextPage.path("content").get(0).asText();
      assertThat(source).startsWith(prefix);
      assertThat(prefix.length()).isGreaterThan(page.path("content").get(0).asText().length());
      assertThat(summaries.getLast()).contains("\"offset\":" + cursor.path("offset").asInt());

      var delivered = new StringBuilder(prefix);
      var latest = next;
      var latestPage = nextPage;
      for (int index = 0; latestPage.path("truncated").asBoolean() && index < 50; index++) {
        int start = latestPage.path("nextRead").path("startLine").asInt();
        String omittedCursor = "{\"files\":[{\"path\":\"a.md\",\"startLine\":" + start + "}]}";
        latest =
            execute(
                route,
                loop,
                new Prompt(latest.conversationHistory(), options),
                call("page-" + index, omittedCursor),
                context);
        latestPage = page(latest);
        assertThat(latestPage.hasNonNull("error")).isFalse();
        for (var text : latestPage.path("content")) delivered.append(text.asText());
      }
      assertThat(latestPage.path("truncated").asBoolean()).isFalse();
      assertThat(delivered.toString()).isEqualTo(source);

      // Reuse the original page history after an external edit: inherited version must reject it.
      Files.writeString(root.resolve("a.md"), "changed source");
      var stale = execute(route, loop, prompt, call("stale", args), context);
      assertThat(page(stale).path("error").asText()).contains("STALE_VERSION");
      assertThat(page(stale).path("content")).isEmpty();
    }
  }

  @Test
  void deliveryDefaultsAllowLargerPages() {
    var properties = new ToolResultDeliveryProperties();
    properties.validate();
    assertThat(properties.getPageTargetTokens()).isEqualTo(8000);
    assertThat(properties.getPageMaxTokens()).isEqualTo(16000);
  }

  private AssistantMessage.ToolCall call(String id, String args) {
    return new AssistantMessage.ToolCall(id, "function", "readMultiFile", args);
  }

  private ChatResponse response(AssistantMessage.ToolCall call) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
  }

  private ToolExecutionResult execute(
      String route,
      ToolLoopSupport loop,
      Prompt prompt,
      AssistantMessage.ToolCall call,
      RunExecutionContext context) {
    return switch (route) {
      case "execute" -> loop.execute(prompt, response(call));
      case "resumable" -> loop.executeResumable(prompt, response(call), context);
      default ->
          loop.executePending(
              prompt, new RunContinuation.PendingTools("", List.of(call), List.of()), context);
    };
  }

  private com.fasterxml.jackson.databind.JsonNode page(ToolExecutionResult result)
      throws Exception {
    var message = (ToolResponseMessage) result.conversationHistory().getLast();
    return json.readTree(message.getResponses().getFirst().responseData()).get(0);
  }
}
