package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.contextbudget.ContextAssembler;
import dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties;
import dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.core.contextbudget.ToolResultCompressor;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

class ExhaustedResumeRecoveryTest {
  @TempDir Path root;
  private static final String PAGE =
      "[{\"path\":\"docs/intro.md\",\"version\":\"v1\",\"startLine\":1,\"content\":[\"known\"],\"error\":null}]";

  private RunExecutionContext context(int count, int replans, int restoration, boolean resumed)
      throws Exception {
    var c =
        new RunExecutionContext(
            "resume",
            new OutputLimitRunBudget(2, 1000),
            new ProgressEvaluator(root),
            new AgentEventFactory(Clock.systemUTC()),
            e -> {});
    c.setRunContext(new AgentRunContext("resume", "conversation", root, "project"));
    c.beginIteration();
    for (String tool : List.of("listFile", "findFile", "readMultiFile")) {
      String result = tool.equals("readMultiFile") ? PAGE : "[\"docs/intro.md\"]";
      c.recordTool(tool, "{}", result, c.evaluator().beforeTool(tool, "{}"));
    }
    c.endIteration();
    var json = new ObjectMapper();
    var saved = (ObjectNode) json.valueToTree(c.progressSnapshot());
    ((ObjectNode) saved.path("budget")).put("stagnationReplans", replans);
    ((ObjectNode) saved.path("continuity").path("detector"))
        .put("stagnationCount", count)
        .put("replanRequested", count >= 4);
    ((ObjectNode) saved.path("continuity").path("evaluator")).put("restorationReads", restoration);
    c.restoreProgress(json.treeToValue(saved, RunExecutionContext.ProgressState.class));
    if (resumed) c.beginContextRestoration();
    return c;
  }

  private ToolCallback tool(String name, java.util.function.Supplier<String> action) {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(name)
            .description(name)
            .inputSchema("{\"type\":\"object\"}")
            .build();
      }

      public String call(String arguments) {
        return action.get();
      }

      public String call(String arguments, ToolContext context) {
        return call(arguments);
      }
    };
  }

  private Prompt prompt(
      RunExecutionContext c, boolean inventory, String owner, AtomicInteger broadCalls) {
    var tools = new ArrayList<ToolCallback>();
    for (String name :
        List.of("listFile", "findFile", "countFileLines", "checkpointShow", "checkpointInspect"))
      tools.add(
          tool(
              name,
              () -> {
                broadCalls.incrementAndGet();
                return "[\"docs/intro.md\"]";
              }));
    tools.add(tool("readMultiFile", () -> PAGE));
    tools.add(
        tool(
            "writeMultiFile",
            () -> {
              try {
                Files.writeString(
                    root.resolve("review.md"),
                    "Known coverage recorded; review remains incomplete");
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
              return "[{\"path\":\"review.md\",\"success\":true}]";
            }));
    var options =
        ToolCallingChatOptions.builder()
            .toolCallbacks(tools)
            .toolContext(Map.of(RunExecutionContext.KEY, c))
            .build();
    var handoff =
        SystemMessage.builder()
            .text(
                "Known inventory: docs/intro.md. Historical evidence; not proof of completed review.")
            .metadata(
                Map.of(
                    "rei.resumeContext",
                    true,
                    "rei.knownFileInventory",
                    inventory,
                    "rei.resumeRunId",
                    owner))
            .build();
    return new Prompt(List.of(handoff, new UserMessage("Continue unfinished work")), options);
  }

  private Set<String> names(Prompt p) {
    return ((ToolCallingChatOptions) p.getOptions())
        .getToolCallbacks().stream()
            .map(t -> t.getToolDefinition().name())
            .collect(java.util.stream.Collectors.toSet());
  }

  private AssistantMessage call(int id, String name, String args) {
    return AssistantMessage.builder()
        .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + id, "function", name, args)))
        .build();
  }

  private ChatModel model(java.util.function.Function<Prompt, AssistantMessage> answer) {
    return new ChatModel() {
      public ChatResponse call(Prompt p) {
        throw new UnsupportedOperationException();
      }

      public Flux<ChatResponse> stream(Prompt p) {
        return Flux.just(new ChatResponse(List.of(new Generation(answer.apply(p)))));
      }
    };
  }

  private Prompt advised(Prompt prompt) {
    var turns = org.mockito.Mockito.mock(dev.mikoto2000.rei.conversation.ConversationTurnStore.class);
    org.mockito.Mockito.when(turns.cancelledContext("conversation"))
        .thenReturn("Historical cancelled request; explicit resumption allowed");
    var request = org.springframework.ai.chat.client.ChatClientRequest.builder().prompt(prompt).build();
    request = new dev.mikoto2000.rei.core.chat.ConversationLifecycleAdvisor(turns, "conversation")
        .before(request, null);
    return new dev.mikoto2000.rei.externalagent.ExternalAgentReviewAdvisor(
        org.mockito.Mockito.mock(dev.mikoto2000.rei.externalagent.ExternalAgentDelegationService.class))
        .before(request, null).prompt();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 7})
  void exhaustedResumeUsesHandedOffInventoryAndRestoresDiscoveryAfterVerifiedWork(int count)
      throws Exception {
    var c = context(count, 2, 8, true);
    var broad = new AtomicInteger();
    var calls = new AtomicInteger();
    var estimator = TokenEstimator.conservative();
    var assembler =
        new ContextAssembler(
            new ContextCompressionProperties(),
            estimator,
            new ConversationSummaryRepository(root.resolve("context")),
            new ToolResultCompressor(
                new RawToolResultStore(root.resolve("context")), estimator, 8000, 2000),
            (previous, messages, budget, user) -> "historical reference",
            null,
            null);
    var delegate =
        model(
            p -> {
              int n = calls.incrementAndGet();
              if (n == 1) {
                // Reproduce the previous model choice if broad discovery is still advertised.
                if (names(p).contains("listFile")) return call(n, "listFile", "{}");
                assertThat(names(p))
                    .doesNotContain(
                        "listFile",
                        "findFile",
                        "countFileLines",
                        "checkpointShow",
                        "checkpointInspect")
                    .contains("readMultiFile", "writeMultiFile");
                assertThat(p.getContents()).contains("Known inventory");
                return call(
                    n,
                    "writeMultiFile",
                    "{\"files\":[{\"path\":\"review.md\",\"content\":\"Known coverage recorded; review remains incomplete\"}]}");
              }
              assertThat(c.detector().stagnationCount()).isZero();
              assertThat(names(p))
                  .contains(
                      "listFile",
                      "findFile",
                      "countFileLines",
                      "checkpointShow",
                      "checkpointInspect");
              return new AssistantMessage("Intermediate coverage recorded; not a completed review");
            });
    new StagnationChatModel(delegate, assembler)
        .stream(advised(prompt(c, true, "resume", broad))).blockLast();
    assertThat(root.resolve("review.md")).exists();
    assertThat(broad).hasValue(0);
    assertThat(calls).hasValue(2);
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
    assertThat(c.progressSnapshot().continuity().evaluator().restorationReads()).isEqualTo(8);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ordinary",
        "workCompleted",
        "replanRemaining",
        "restorationRemaining",
        "noInventory",
        "wrongOwner",
        "readOnly",
        "conversation"
      })
  void ordinaryOrUnverifiedRecoveryKeepsDiscoveryTools(String condition) throws Exception {
    var c =
        context(
            condition.equals("workCompleted") ? 0 : 7,
            condition.equals("replanRemaining") ? 1 : 2,
            condition.equals("restorationRemaining") ? 7 : 8,
            !condition.equals("ordinary"));
    if (condition.equals("workCompleted")) c.completeSubgoal("verified");
    if (condition.equals("readOnly") || condition.equals("conversation"))
      c.setRunContext(
          new AgentRunContext(
              "resume",
              "conversation",
              root,
              "project",
              AgentRunContext.RequestSource.SHELL,
              condition.equals("readOnly")
                  ? AgentRunContext.Mode.READ_ONLY
                  : AgentRunContext.Mode.CONVERSATION,
              false));
    var delegate =
        model(
            p -> {
              assertThat(names(p))
                  .contains(
                      "listFile",
                      "findFile",
                      "countFileLines",
                      "checkpointShow",
                      "checkpointInspect");
              throw new java.util.concurrent.CancellationException();
            });
    assertThatThrownBy(
            () ->
                new StagnationChatModel(delegate)
                    .stream(
                            prompt(
                                c,
                                !condition.equals("noInventory"),
                                condition.equals("wrongOwner") ? "different" : "resume",
                                new AtomicInteger()))
                        .blockLast())
        .isInstanceOf(java.util.concurrent.CancellationException.class);
  }

  @Test
  void newCoverageKeepsRecoveryFocusedUntilActualWorkAdvances() throws Exception {
    var c = context(0, 2, 8, true);
    var initial = prompt(c, true, "resume", new AtomicInteger());
    var options = (ToolCallingChatOptions) initial.getOptions();
    var callbacks = new ArrayList<>(options.getToolCallbacks());
    callbacks.removeIf(t -> t.getToolDefinition().name().equals("readMultiFile"));
    callbacks.add(tool("readMultiFile", () -> PAGE.replace("docs/intro.md", "docs/unseen.md")));
    var calls = new AtomicInteger();
    var delegate =
        model(
            p -> {
              int n = calls.incrementAndGet();
              if (n == 1) {
                assertThat(names(p))
                    .doesNotContain("listFile", "countFileLines")
                    .contains("readMultiFile");
                return call(n, "readMultiFile", "{}");
              }
              if (n == 2) {
                assertThat(c.detector().stagnationCount()).isZero();
                assertThat(c.needsResumeWorkRecovery()).isTrue();
                assertThat(names(p)).doesNotContain("listFile", "countFileLines");
                assertThat(p.getContents()).contains("intermediate", "not proof");
                return call(
                    n,
                    "writeMultiFile",
                    "{\"files\":[{\"path\":\"review.md\",\"content\":\"Partial verified coverage; assessment remains incomplete\"}]}");
              }
              assertThat(c.needsResumeWorkRecovery()).isFalse();
              assertThat(names(p)).contains("listFile", "countFileLines");
              return new AssistantMessage(
                  "Intermediate evidence recorded; review remains incomplete");
            });
    new StagnationChatModel(delegate)
        .stream(
                new Prompt(
                    initial.getInstructions(), options.mutate().toolCallbacks(callbacks).build()))
            .blockLast();
    assertThat(root.resolve("review.md")).exists();
    assertThat(calls).hasValue(3);
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
    assertThat(c.progressSnapshot().continuity().evaluator().restorationReads()).isEqualTo(8);
  }

  @Test
  void olderRestoredHandoffCannotOverrideCurrentUnverifiedInventory() throws Exception {
    var c = context(7, 2, 8, true);
    var current = prompt(c, false, "resume", new AtomicInteger());
    var messages = new ArrayList<>(current.getInstructions());
    messages.add(
        1,
        SystemMessage.builder()
            .text("Older saved continuation handoff")
            .metadata(
                Map.of(
                    "rei.resumeContext",
                    true,
                    "rei.resumeRunId",
                    "resume",
                    "rei.knownFileInventory",
                    true))
            .build());
    var delegate =
        model(
            p -> {
              assertThat(names(p))
                  .contains(
                      "listFile",
                      "findFile",
                      "countFileLines",
                      "checkpointShow",
                      "checkpointInspect");
              throw new java.util.concurrent.CancellationException();
            });
    assertThatThrownBy(
            () ->
                new StagnationChatModel(delegate)
                    .stream(new Prompt(messages, current.getOptions())).blockLast())
        .isInstanceOf(java.util.concurrent.CancellationException.class);
  }

  @Test
  void duplicateBodyReadsStillStopWithoutAnyRefundOrExtraGrace() throws Exception {
    var c = context(7, 2, 8, true);
    assertThatThrownBy(
            () ->
                new StagnationChatModel(model(p -> call(1, "readMultiFile", "{}")))
                    .stream(prompt(c, true, "resume", new AtomicInteger())).blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(c.detector().stagnationCount()).isEqualTo(8);
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
    assertThat(c.progressSnapshot().continuity().evaluator().restorationReads()).isEqualTo(8);
  }

  @Test
  void modelCannotExecuteAHiddenDiscoveryCallback() throws Exception {
    var c = context(7, 2, 8, true);
    var executed = new AtomicInteger();
    assertThatThrownBy(
            () ->
                new StagnationChatModel(model(p -> call(1, "listFile", "{}")))
                    .stream(prompt(c, true, "resume", executed)).blockLast())
        .isInstanceOf(RuntimeException.class);
    assertThat(executed).hasValue(0);
  }
}
