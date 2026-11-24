package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** Exercises durable approval recovery with no Java object surviving the writer process. */
@org.junit.jupiter.api.Tag("integration")
class ApprovalProcessRestartTest {
  @TempDir Path directory;

  @Test
  void anotherJvmRestoresTheOriginalTraceAndExecutesOnlyTheRemainingTool() throws Exception {
    worker("write");
    worker("read");
    assertThat(Files.readString(directory.resolve("effects.txt"))).isEqualTo("first\nsecond\n");
  }

  private void worker(String mode) throws Exception {
    var output = directory.resolve(mode + ".log");
    var arguments = directory.resolve(mode + ".args");
    var values =
        List.of(
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            Worker.class.getName(),
            mode,
            directory.toString());
    Files.write(
        arguments,
        values.stream()
            .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .toList());
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile());
    process.environment().put("REI_DATA_DIR", directory.resolve("data").toString());
    var child = process.start();
    boolean completed = child.waitFor(40, TimeUnit.SECONDS);
    if (!completed) child.destroyForcibly();
    assertThat(completed).as("owned fixture JVM completed: %s", mode).isTrue();
    assertThat(child.exitValue()).as("fixture JVM: %s", Files.readString(output)).isZero();
  }

  public static class Worker {
    public static void main(String[] arguments) throws Exception {
      Path directory = Path.of(arguments[1]);
      if (!directory.resolve("data").toString().equals(System.getenv("REI_DATA_DIR")))
        throw new IllegalStateException("Isolated fixture data directory required");
      var source = new org.sqlite.SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + directory.resolve("approval.db"));
      var payloads = new ModelMessagePayloadStore(source);
      var approvals = new ApprovalContinuationStore(source);
      var owner = new AgentRunContext("current", "chat", directory, "project");
      var exchange = payloads.newExchange(owner);
      var origin = new ModelMessagePayloadStore.Origin(exchange.ownerKey(), "endpoint");
      if (arguments[0].equals("write")) {
        var user = new UserMessage("original fixture input");
        var userRef = exchange.withKey("input").reference(user);
        var first = new AssistantMessage.ToolCall("first", "function", "fixture", "{}");
        var second = new AssistantMessage.ToolCall("second", "function", "fixture", "{}");
        var assistant =
            AssistantMessage.builder()
                .parts(
                    List.of(
                        new org.springframework.ai.chat.messages.part.ReasoningPart(
                            null,
                            "fixture summary",
                            new org.springframework.ai.chat.messages.part.OpaquePayload(
                                "openai", "reasoning", "opaque fixture bytes"),
                            Map.of("id", "rs_fixture")),
                        new org.springframework.ai.chat.messages.part.TextPart(
                            "pending", null, Map.of("phase", "commentary")),
                        new org.springframework.ai.chat.messages.part.ToolCallPart(
                            first, null, Map.of("id", "fc_first")),
                        new org.springframework.ai.chat.messages.part.ToolCallPart(
                            second, null, Map.of("id", "fc_second"))))
                .properties(
                    Map.of(
                        "rei.llm.replay-origin-owner",
                        origin.owner(),
                        "rei.llm.replay-origin-endpoint",
                        origin.endpoint()))
                .build();
        var assistantRef = exchange.reference(assistant);
        var result = new ToolResponseMessage.ToolResponse("first", "fixture", "saved result");
        var prefixRef =
            exchange
                .withKey(exchange.key() + "/prefix:1")
                .reference(ToolResponseMessage.builder().responses(List.of(result)).build());
        Files.writeString(directory.resolve("effects.txt"), "first\n");
        var systemRef =
            exchange
                .withKey("instructions")
                .reference(
                    org.springframework.ai.chat.messages.SystemMessage.builder()
                        .text("original fixture context")
                        .metadata(Map.of("rei.workContext", true))
                        .build());
        var history =
            new ModelMessagePayloadStore.History(
                1,
                owner.runId(),
                origin,
                List.of(
                    new ModelMessagePayloadStore.History.Entry("input/USER", userRef),
                    new ModelMessagePayloadStore.History.Entry(
                        exchange.key() + "/ASSISTANT", assistantRef),
                    new ModelMessagePayloadStore.History.Entry(
                        exchange.key() + "/TOOL", prefixRef)),
                new ModelMessagePayloadStore.History.Input(
                    List.of(
                        new ModelMessagePayloadStore.History.Entry(
                            "instructions/SYSTEM", systemRef, 7L),
                        new ModelMessagePayloadStore.History.Entry("input/USER", userRef)),
                    1));
        approvals.save(
            "approval",
            owner,
            "original fixture input",
            List.of(user),
            new RunContinuation.PendingTools(
                "pending",
                List.of(first, second),
                List.of(result),
                Set.of(),
                assistantRef,
                prefixRef),
            new OutputLimitRunBudget(2, 5).snapshot(),
            false,
            history);
        approvals.guidance(owner, List.of(new UserMessage("human resume guidance")));
        return;
      }
      var saved = approvals.saved(approvals.find("approval").orElseThrow());
      assertThat(saved.messages())
          .extracting(org.springframework.ai.chat.messages.Message::getText)
          .containsExactly(
              "original fixture context", "original fixture input", "human resume guidance");
      assertThat(saved.messages().getFirst().getMetadata())
          .containsEntry("rei.workContext", true)
          .containsEntry("rei.historySequence", 7L);
      var callback =
          new org.springframework.ai.tool.ToolCallback() {
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
              return org.springframework.ai.tool.definition.ToolDefinition.builder()
                  .name("fixture")
                  .description("fixture")
                  .inputSchema("{\"type\":\"object\"}")
                  .build();
            }

            public String call(String input) {
              throw new AssertionError("Tool context required");
            }

            public String call(
                String input, org.springframework.ai.chat.model.ToolContext context) {
              assertThat(context.getContext().get("toolCallId")).isEqualTo("second");
              try {
                Files.writeString(
                    directory.resolve("effects.txt"),
                    "second\n",
                    java.nio.file.StandardOpenOption.APPEND);
              } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
              }
              return "remaining result";
            }
          };
      var execution =
          new RunExecutionContext(
              owner.runId(),
              new OutputLimitRunBudget(2, 5),
              new ProgressEvaluator(directory),
              new AgentEventFactory(Clock.systemUTC()),
              ignored -> {});
      execution.setRunContext(owner);
      execution.resume(saved);
      execution.setModelOptions(
          ToolCallingChatOptions.builder()
              .toolCallbacks(List.of(callback))
              .toolContext(
                  Map.of(
                      ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
                      exchange,
                      ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
                      origin))
              .build());
      var original =
          (AssistantMessage)
              payloads.load(exchange.scope(), saved.pendingTools().assistantReference());
      assertThat(original.getParts()).hasSize(4);
      var reasoning =
          (org.springframework.ai.chat.messages.part.ReasoningPart) original.getParts().getFirst();
      assertThat(reasoning)
          .isEqualTo(
              new org.springframework.ai.chat.messages.part.ReasoningPart(
                  null,
                  "fixture summary",
                  new org.springframework.ai.chat.messages.part.OpaquePayload(
                      "openai", "reasoning", "opaque fixture bytes"),
                  Map.of("id", "rs_fixture")));
      var result =
          new ToolLoopSupport()
              .executePending(
                  new Prompt(saved.messages(), execution.modelOptions()),
                  saved.pendingTools(),
                  execution);
      assertThat(result.conversationHistory()).hasSize(5);
      var trace = execution.protocolHistorySnapshot();
      assertThat(trace.entries()).hasSize(4);
      assertThat(trace.entries().get(1)).isEqualTo(saved.protocolHistory().entries().get(1));
      assertThat(payloads.load(exchange.scope(), trace.entries().getLast().reference()).getText())
          .as("human guidance remains in completed task history")
          .isEqualTo("human resume guidance");
      assertThat(payloads.load(exchange.scope(), trace.entries().getFirst().reference()).getText())
          .isEqualTo("original fixture input");
      assertThat(
              (ToolResponseMessage)
                  payloads.load(exchange.scope(), trace.entries().get(2).reference()))
          .extracting(message -> message.getResponses().size())
          .isEqualTo(2);
    }
  }
}
