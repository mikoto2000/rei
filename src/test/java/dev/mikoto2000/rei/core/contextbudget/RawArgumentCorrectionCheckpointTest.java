package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.checkpoint.CheckpointProperties;
import dev.mikoto2000.rei.checkpoint.CheckpointReconciler;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import dev.mikoto2000.rei.core.actionplan.ActionPlan;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import dev.mikoto2000.rei.core.checkpoint.CheckpointStore;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.taskstate.TaskState;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

@Tag("integration")
class RawArgumentCorrectionCheckpointTest {
  @TempDir Path dir;

  private final ObjectMapper json = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void correctionCanBeCheckpointedAndReturnedToTheModelWithoutClaimingRawPageProvenance(
      boolean pendingReplay) throws Exception {
    try (var fixture = fixture();
        var scope = AgentRunScope.open(fixture.owner())) {
      String ref = fixture.store().save("chat", "run", "runCommand", "build", "build failed");
      var invalid = invalidCall(ref);
      var output = AssistantMessage.builder().toolCalls(List.of(invalid)).build();
      var loop = new ToolLoopSupport();
      var execution =
          pendingReplay
              ? loop.executePending(
                  fixture.prompt(),
                  new RunContinuation.PendingTools("", List.of(invalid), List.of()),
                  fixture.execution())
              : loop.executeResumable(
                  fixture.prompt(),
                  new ChatResponse(List.of(new Generation(output))),
                  fixture.execution());
      var message = (ToolResponseMessage) execution.conversationHistory().getLast();
      String correction = message.getResponses().getFirst().responseData();

      assertThat(json.readTree(correction).path("error").asText())
          .isEqualTo("INVALID_TOOL_ARGUMENTS");
      assertThat(json.readTree(correction).path("executed").asBoolean()).isFalse();
      assertThat(ToolResultCompressor.policy(message, invalid.id()))
          .isEqualTo(ResultDelivery.Kind.GENERAL);
      assertThat(execution.returnDirect()).isFalse();
      assertThat(fixture.store().bodyReadCount()).isZero();
      assertThatCode(
              () ->
                  fixture
                      .checkpoints()
                      .preserveResults(
                          fixture.owner(),
                          execution.conversationHistory(),
                          fixture.execution().progressSnapshot()))
          .doesNotThrowAnyException();
      assertThat(
              fixture
                  .repository()
                  .findByRun("project", "run")
                  .orElseThrow()
                  .progress()
                  .budget()
                  .argumentCorrections())
          .isEqualTo(1);

      var compressor = compressor(fixture.store());
      var nextPrompt =
          compressor.fit(new Prompt(execution.conversationHistory()), "chat", "run", 50);
      var delivered = (ToolResponseMessage) nextPrompt.getInstructions().getLast();
      assertThat(delivered.getResponses().getFirst().responseData()).isEqualTo(correction);
      assertThat(ToolResultCompressor.policy(delivered, invalid.id()))
          .isEqualTo(ResultDelivery.Kind.GENERAL);
    }
  }

  @Test
  void savedCompletedCorrectionRetainsItsClassificationWhenAnotherCallContinues() throws Exception {
    try (var fixture = fixture();
        var scope = AgentRunScope.open(fixture.owner())) {
      String raw = "ビルド結果と指摘🙂".repeat(2000);
      String ref = fixture.store().save("chat", "run", "runCommand", "build", raw);
      var invalid = invalidCall(ref);
      var loop = new ToolLoopSupport();
      var first =
          loop.executePending(
              fixture.prompt(),
              new RunContinuation.PendingTools("", List.of(invalid), List.of()),
              fixture.execution());
      var correction =
          ((ToolResponseMessage) first.conversationHistory().getLast()).getResponses().getFirst();
      var next = validCall(ref);
      var saved =
          new RunContinuation.PendingTools(
              "continue reading",
              List.of(invalid, next),
              List.of(correction),
              Set.of(invalid.id()));
      var restored =
          json.readValue(json.writeValueAsString(saved), RunContinuation.PendingTools.class);
      var resumed = loop.executePending(fixture.prompt(), restored, fixture.execution());
      var message = (ToolResponseMessage) resumed.conversationHistory().getLast();

      assertThat(message.getResponses()).hasSize(2);
      assertThat(message.getResponses().getFirst()).isEqualTo(correction);
      assertThat(ToolResultCompressor.policy(message, invalid.id()))
          .isEqualTo(ResultDelivery.Kind.GENERAL);
      assertThat(ToolResultCompressor.policy(message, next.id()))
          .isEqualTo(ResultDelivery.Kind.RAW_RESULT_PAGE);
      var page = RawResultPage.parse(message.getResponses().get(1).responseData());
      assertThat(page.content()).isEqualTo(raw.substring(0, page.nextOffset()));
      assertThat(fixture.execution().progressSnapshot().budget().argumentCorrections())
          .isEqualTo(1);
      assertThatCode(
              () ->
                  fixture
                      .checkpoints()
                      .preserveResults(fixture.owner(), resumed.conversationHistory()))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void legacySavedBatchWithoutCorrectionMetadataStillResumes() throws Exception {
    try (var fixture = fixture();
        var scope = AgentRunScope.open(fixture.owner())) {
      String ref = fixture.store().save("chat", "run", "runCommand", "build", "build output");
      var legacy = json.createObjectNode();
      legacy.put("text", "continue reading");
      legacy.set("calls", json.valueToTree(List.of(validCall(ref))));
      legacy.set("completed", json.createArrayNode());
      var pending = json.treeToValue(legacy, RunContinuation.PendingTools.class);

      assertThat(pending.argumentCorrections()).isEmpty();
      var result =
          new ToolLoopSupport().executePending(fixture.prompt(), pending, fixture.execution());
      var message = (ToolResponseMessage) result.conversationHistory().getLast();
      assertThat(ToolResultCompressor.policy(message, "valid-page"))
          .isEqualTo(ResultDelivery.Kind.RAW_RESULT_PAGE);
      assertThat(RawResultPage.parse(message.getResponses().getFirst().responseData()).content())
          .isEqualTo("build output");
    }
  }

  @Test
  void correctionMetadataCannotDemoteAnUnexecutedPageCall() {
    try (var fixture = fixture();
        var scope = AgentRunScope.open(fixture.owner())) {
      String ref = fixture.store().save("chat", "run", "runCommand", "build", "build output");
      var pending =
          new RunContinuation.PendingTools(
              "", List.of(validCall(ref)), List.of(), Set.of("valid-page"));
      assertThatThrownBy(
              () ->
                  new ToolLoopSupport()
                      .executePending(fixture.prompt(), pending, fixture.execution()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("completed Tool");
      assertThat(fixture.store().bodyReadCount()).isZero();
    }
  }

  @Test
  void genuineRawPagesKeepExactProvenanceThroughCheckpointingAndBudgetFitting() {
    try (var fixture = fixture();
        var scope = AgentRunScope.open(fixture.owner())) {
      String raw = "指摘🙂\\\"\r\n".repeat(4000);
      String ref = fixture.store().save("chat", "run", "runCommand", "build", raw);
      var call = validCall(ref);
      var execution =
          new ToolLoopSupport()
              .executeResumable(
                  fixture.prompt(),
                  new ChatResponse(
                      List.of(
                          new Generation(
                              AssistantMessage.builder().toolCalls(List.of(call)).build()))),
                  fixture.execution());
      var message = (ToolResponseMessage) execution.conversationHistory().getLast();

      assertThat(ToolResultCompressor.policy(message, call.id()))
          .isEqualTo(ResultDelivery.Kind.RAW_RESULT_PAGE);
      fixture.checkpoints().preserveResults(fixture.owner(), execution.conversationHistory());
      assertThat(fixture.repository().findByRun("project", "run").orElseThrow().evidence())
          .anyMatch(
              evidence ->
                  evidence.origin().equals("RAW_RESULT_REFERENCE")
                      && evidence.summary().equals(ref));

      var fitted = compressor(fixture.store()).fit(new Prompt(message), "chat", "run", 450);
      var delivered = (ToolResponseMessage) fitted.getInstructions().getLast();
      var page = RawResultPage.parse(delivered.getResponses().getFirst().responseData());
      assertThat(ToolResultCompressor.policy(delivered, call.id()))
          .isEqualTo(ResultDelivery.Kind.RAW_RESULT_PAGE);
      assertThat(page.rawResultRef()).isEqualTo(ref);
      assertThat(page.offset()).isZero();
      assertThat(page.content()).isEqualTo(raw.substring(0, page.nextOffset()));
      assertThat(
              ContextBudgetManager.estimateMessages(
                  fitted.getInstructions(), TokenEstimator.conservative()))
          .isLessThanOrEqualTo(450);
      assertThat(page.hasMore()).isTrue();
    }
  }

  @Test
  void malformedRegisteredPageStillFailsCheckpointingAndBudgetFitting() {
    try (var fixture = fixture();
        var scope = AgentRunScope.open(fixture.owner())) {
      String raw = "指摘".repeat(4000);
      String ref = fixture.store().save("chat", "run", "runCommand", "build", raw);
      var call = validCall(ref);
      var execution =
          new ToolLoopSupport()
              .executeResumable(
                  fixture.prompt(),
                  new ChatResponse(
                      List.of(
                          new Generation(
                              AssistantMessage.builder().toolCalls(List.of(call)).build()))),
                  fixture.execution());
      var registered = (ToolResponseMessage) execution.conversationHistory().getLast();
      var broken =
          ToolResponseMessage.builder()
              .metadata(registered.getMetadata())
              .responses(
                  List.of(
                      new ToolResponseMessage.ToolResponse(
                          call.id(), call.name(), "private malformed page ".repeat(200))))
              .build();

      assertThatThrownBy(
              () -> fixture.checkpoints().preserveResults(fixture.owner(), List.of(broken)))
          .isInstanceOf(RawResultPageException.class)
          .hasMessageNotContaining("private");
      assertThatThrownBy(
              () -> compressor(fixture.store()).fit(new Prompt(broken), "chat", "run", 100))
          .isInstanceOf(RawResultPageException.class)
          .hasMessageNotContaining("private");
    }
  }

  private static ToolResultCompressor compressor(RawToolResultStore store) {
    var compressor = new ToolResultCompressor(store, TokenEstimator.conservative(), 8000, 2000);
    var delivery = new ToolResultDeliveryProperties();
    delivery.setPageMinTokens(1);
    compressor.setDeliveryProperties(delivery);
    return compressor;
  }

  private static AssistantMessage.ToolCall invalidCall(String ref) {
    return new AssistantMessage.ToolCall(
        "invalid-page",
        "function",
        "readRawToolResult",
        "{\"rawResultRef\":\"" + ref + "\",\"nextOffset\":\"3433\",\"limit\":16000}");
  }

  private static AssistantMessage.ToolCall validCall(String ref) {
    return new AssistantMessage.ToolCall(
        "valid-page",
        "function",
        "readRawToolResult",
        "{\"rawResultRef\":\"" + ref + "\",\"offset\":0,\"limit\":16000}");
  }

  private Fixture fixture() {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + dir.resolve("checkpoints.db"));
    var settings = new CheckpointProperties();
    var repository = new PersistentCheckpointRepository(source, settings);
    var beans = new StaticListableBeanFactory();
    var bus = new InMemoryAgentEventBus();
    var checkpoints =
        new PersistentCheckpointService(
            repository,
            new CheckpointReconciler(null),
            settings,
            bus,
            beans.getBeanProvider(ConversationInputRouter.class),
            beans.getBeanProvider(RunRegistry.class),
            beans.getBeanProvider(RunService.class),
            beans.getBeanProvider(ActionPlan.class),
            beans.getBeanProvider(WorkingSet.class),
            beans.getBeanProvider(TaskState.class),
            beans.getBeanProvider(CheckpointStore.class));
    var store = new RawToolResultStore(dir.resolve("raw-results"));
    ReflectionTestUtils.setField(checkpoints, "rawResults", store);
    var owner = new AgentRunContext("run", "chat", dir, "project");
    checkpoints.start(owner, "read build output");
    var execution =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(1, 10),
            new ProgressEvaluator(dir),
            new AgentEventFactory(Clock.systemUTC()),
            bus);
    execution.setRunContext(owner);
    var callbacks =
        DeliveryToolRegistration.provider(new RawToolResultTools(store)).getToolCallbacks();
    var prompt =
        new Prompt(
            "read build output",
            ToolCallingChatOptions.builder().toolCallbacks(List.of(callbacks)).build());
    return new Fixture(store, repository, checkpoints, owner, execution, prompt);
  }

  private record Fixture(
      RawToolResultStore store,
      PersistentCheckpointRepository repository,
      PersistentCheckpointService checkpoints,
      AgentRunContext owner,
      RunExecutionContext execution,
      Prompt prompt)
      implements AutoCloseable {
    @Override
    public void close() {
      checkpoints.close();
    }
  }
}
