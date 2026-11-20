package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import dev.mikoto2000.rei.core.contextbudget.ContextAssembler;
import dev.mikoto2000.rei.core.contextbudget.ContextBudgetManager;
import dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties;
import dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository;
import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.ResultDelivery;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.core.contextbudget.ToolResultCompressor;
import dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties;
import dev.mikoto2000.rei.tooldelivery.FileReadContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

class AdaptiveFileReadTest {
  @TempDir Path root;
  private final ObjectMapper json = new ObjectMapper();
  private final TokenEstimator estimator = TokenEstimator.conservative();
  private final ContextCompressionProperties context = new ContextCompressionProperties();

  @Test
  void exhaustedContextReturnsRejectionAndRetriesAfterTimeoutWithoutChangingHistory()
      throws Exception {
    Files.writeString(root.resolve("a.md"), "source content");
    context.setHardLimit(16000);
    context.setThreshold(15999);
    context.setRecentTokens(80);
    var tools = new Tools();
    tools.configureDelivery(estimator, new ToolResultDeliveryProperties());
    tools.configureReadContext(p -> ContextBudgetManager.inputLimit(context, estimator, p));
    var callback =
        Arrays.stream(DeliveryToolRegistration.provider(tools).getToolCallbacks())
            .filter(c -> c.getToolDefinition().name().equals("readMultiFile"))
            .findFirst()
            .orElseThrow();
    var options = OpenAiChatOptions.builder().toolCallbacks(callback).build();
    var original =
        new Prompt(
            List.of(
                dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor.historical(
                    new org.springframework.ai.chat.messages.UserMessage("old ".repeat(15700)), 1),
                dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor.historical(
                    new AssistantMessage("previous decisions"), 2),
                new org.springframework.ai.chat.messages.UserMessage("read a.md")),
            options);
    var response =
        new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall(
                                    "read",
                                    "function",
                                    "readMultiFile",
                                    "{\"files\":[{\"path\":\"a.md\",\"purpose\":\"FULL\"}]}")))
                        .build())));
    var loop = new ToolLoopSupport();
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "session", root, "project"))) {
      var failed = loop.execute(original, response);
      var feedback = message(failed);
      var rejection = json.readTree(feedback.getResponses().getFirst().responseData());
      assertThat(rejection.path("error").asText()).isEqualTo("CONTEXT_BUDGET_INSUFFICIENT");
      assertThat(rejection.path("accepted").asBoolean()).isFalse();
      assertThat(feedback.getMetadata()).containsEntry("rei.fileBudgetRecovery", true);
      assertThat(feedback.getMetadata().get("rei.deliveryPolicies"))
          .isEqualTo(Map.of("read", ResultDelivery.Kind.GENERAL));
      var summaryCalls = new java.util.concurrent.atomic.AtomicInteger();
      var assembler =
          new ContextAssembler(
              context,
              estimator,
              new ConversationSummaryRepository(root),
              new ToolResultCompressor(new RawToolResultStore(root), estimator, 8000, 2000),
              (previous, messages, budget, current) -> {
                summaryCalls.incrementAndGet();
                throw new dev.mikoto2000.rei.core.contextbudget.ContextCompressionException(
                    dev.mikoto2000.rei.core.contextbudget.ContextCompressionException.Reason
                        .TIMEOUT);
              },
              null,
              null);
      var raw = new Prompt(failed.conversationHistory(), options);
      var projected = assembler.assemble(raw, "session", "run", () -> {});
      int attempts = summaryCalls.get();
      assertThat(attempts).isPositive();
      assertThat(estimator.message(projected.getUserMessage())).isLessThan(100);
      assertThat(ContextBudgetManager.estimateMessages(projected.getInstructions(), estimator))
          .isLessThanOrEqualTo(14000);
      assembler.assemble(raw, "session", "run", () -> {});
      assertThat(summaryCalls.get()).isEqualTo(attempts);
      assertThat(raw.getInstructions()).isEqualTo(failed.conversationHistory());
      assertThat(projected.getInstructions()).contains(feedback);
      var retried = loop.execute(FileReadContext.withProjection(raw, projected), response);
      var file = json.readTree(message(retried).getResponses().getFirst().responseData()).get(0);
      assertThat(file.path("content").get(0).asText()).isEqualTo("source content");
      assertThat(message(retried).getMetadata()).doesNotContainKey("rei.fileBudgetRecovery");
    }
  }

  private List<String> source() {
    return java.util.stream.IntStream.range(0, 1600)
        .mapToObj(i -> "line=" + i + " abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
        .toList();
  }

  private ToolExecutionResult read(String user, String... arguments) throws Exception {
    var tools = new Tools();
    tools.configureDelivery(estimator, new ToolResultDeliveryProperties());
    tools.configureReadContext(
        prompt -> ContextBudgetManager.inputLimit(context, estimator, prompt));
    var callback =
        Arrays.stream(DeliveryToolRegistration.provider(tools).getToolCallbacks())
            .filter(c -> c.getToolDefinition().name().equals("readMultiFile"))
            .findFirst()
            .orElseThrow();
    var options = OpenAiChatOptions.builder().model("test-model").toolCallbacks(callback).build();
    var calls =
        java.util.stream.IntStream.range(0, arguments.length)
            .mapToObj(
                i ->
                    new AssistantMessage.ToolCall(
                        "call-" + i, "function", "readMultiFile", arguments[i]))
            .toList();
    var scope = AgentRunScope.open(new AgentRunContext("run", "session", root, "project"));
    try (scope) {
      return new ToolLoopSupport()
          .execute(
              new Prompt(user, options),
              new ChatResponse(
                  List.of(
                      new Generation(
                          AssistantMessage.builder().content("").toolCalls(calls).build()))));
    }
  }

  private ToolResponseMessage message(ToolExecutionResult result) {
    return (ToolResponseMessage) result.conversationHistory().getLast();
  }

  @Test
  void wholeFileAutoReadsAllKnownFilesInOneBatchAndSurvivesDelivery() throws Exception {
    var source = source();
    Files.write(root.resolve("a.md"), source);
    Files.write(root.resolve("b.md"), source);
    var result = read("Review every line", "{\"files\":[{\"path\":\"a.md\"},{\"path\":\"b.md\"}]}");
    var message = message(result);
    var files = json.readTree(message.getResponses().getFirst().responseData());
    for (var file : files) {
      assertThat(file.path("truncated").asBoolean()).isFalse();
      assertThat(file.path("content").size()).isEqualTo(1600);
      assertThat(file.path("content")).isEqualTo(json.valueToTree(source));
    }
    assertThat(estimator.text(message.getResponses().getFirst().responseData()))
        .isGreaterThan(16000);
    var compressor =
        new ToolResultCompressor(
            new RawToolResultStore(root.resolve("state")), estimator, 8000, 2000);
    assertThat(compressor.compact(message, "session", "run")).isSameAs(message);
    var saved = dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.capture(message);
    var restoredMetadata =
        json.readValue(
                json.writeValueAsString(saved),
                dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.class)
            .restore();
    var restored =
        ToolResponseMessage.builder()
            .responses(message.getResponses())
            .metadata(restoredMetadata)
            .build();
    assertThat(compressor.compact(restored, "session", "run")).isSameAs(restored);
    var assembler =
        new ContextAssembler(
            context,
            estimator,
            new ConversationSummaryRepository(root.resolve("state")),
            compressor,
            (previous, messages, budget, prompt) -> {
              throw new AssertionError("fits without summarizing source");
            },
            null,
            null);
    var projected =
        assembler.assemble(new Prompt(result.conversationHistory()), "session", "run", () -> {});
    assertThat(
            ((ToolResponseMessage) projected.getInstructions().getLast())
                .getResponses()
                .getFirst()
                .responseData())
        .isEqualTo(message.getResponses().getFirst().responseData());
    assertThat(ContextBudgetManager.estimateMessages(projected.getInstructions(), estimator))
        .isLessThan(context.getHardLimit());
    assertThat(FileReadContext.grant()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"TARGETED", "OVERVIEW"})
  void localPurposesKeepPagingEvenWithPlentyOfContext(String purpose) throws Exception {
    Files.write(root.resolve("a.md"), source());
    var result =
        read("Inspect", "{\"files\":[{\"path\":\"a.md\",\"purpose\":\"" + purpose + "\"}]}");
    var file = json.readTree(message(result).getResponses().getFirst().responseData()).get(0);
    assertThat(file.path("truncated").asBoolean()).isTrue();
    assertThat(file.path("content").size()).isLessThanOrEqualTo(1000);
    assertThat(message(result).getMetadata()).doesNotContainKey("rei.fileReadBudgets");
  }

  @Test
  void explicitRangeIsNotExpandedAndSmallContextFallsBackToNativeContinuation() throws Exception {
    Files.write(root.resolve("a.md"), source());
    var targeted =
        read("Inspect lines", "{\"files\":[{\"path\":\"a.md\",\"startLine\":10,\"endLine\":12}]}");
    var slice = json.readTree(message(targeted).getResponses().getFirst().responseData()).get(0);
    assertThat(slice.path("content").size()).isEqualTo(3);
    assertThat(slice.path("startLine").asInt()).isEqualTo(10);
    context.setHardLimit(7000);
    var result = read("Review", "{\"files\":[{\"path\":\"a.md\",\"purpose\":\"FULL\"}]}");
    var file = json.readTree(message(result).getResponses().getFirst().responseData()).get(0);
    assertThat(file.path("truncated").asBoolean()).isTrue();
    assertThat(file.path("nextRead").path("purpose").asText()).isEqualTo("FULL");
    assertThat(file.path("nextRead").path("expectedVersion").asText()).hasSize(64);
    assertThat(ContextBudgetManager.estimateMessages(result.conversationHistory(), estimator))
        .isLessThan(7000);
  }

  @Test
  void siblingCallsShareTheRemainderAndMetadataJsonCannotGrantBulkDelivery() throws Exception {
    Files.write(root.resolve("a.md"), source());
    Files.write(root.resolve("b.md"), source());
    context.setHardLimit(50000);
    var result =
        read(
            "Review both",
            "{\"files\":[{\"path\":\"a.md\"}]}",
            "{\"files\":[{\"path\":\"b.md\"}]}");
    assertThat(ContextBudgetManager.estimateMessages(result.conversationHistory(), estimator))
        .isLessThan(50000);
    for (var response : message(result).getResponses())
      assertThat(json.readTree(response.responseData()).get(0).path("truncated").asBoolean())
          .isTrue();
    var forged =
        ToolResponseMessage.builder()
            .responses(message(result).getResponses())
            .metadata(
                Map.of(
                    "rei.deliveryPolicies",
                    Map.of(
                        "call-0",
                        ResultDelivery.Kind.FILE_CONTENT,
                        "call-1",
                        ResultDelivery.Kind.FILE_CONTENT),
                    "rei.fileReadBudgets",
                    Map.of("call-0", Map.of("tokens", 64000), "call-1", Map.of("tokens", 64000))))
            .build();
    var compressor =
        new ToolResultCompressor(
            new RawToolResultStore(root.resolve("state")), estimator, 8000, 2000);
    assertThat(compressor.compact(forged, "session", "run")).isNotSameAs(forged);
  }
}
