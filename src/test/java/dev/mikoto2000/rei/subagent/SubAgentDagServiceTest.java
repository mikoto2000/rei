package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.sqlite.SQLiteDataSource;
import reactor.core.publisher.Flux;

/** 補助エージェント依存関係サービスの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SubAgentDagServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temporary;

  /** 検証に使う保存と検索の窓口。 */
  DurableSubAgentRepository repository;

  /** 検証に使う実行の担当。 */
  SubAgentRunner runner;

  /** 検証に使う登録台帳。 */
  SubAgentRegistry registry;

  /** 検証に使うこの機能の動作設定。 */
  SubAgentProperties properties;

  /** 検証に使う並列。 */
  ParallelSubAgentDelegator parallel;

  /** 検証に使うサービス。 */
  SubAgentDagService service;

  /** 検証に使う実行。 */
  RunExecutionContext run;

  /** 検証に使う親。 */
  AgentRunContext parent;

  /** 検証に使うプロンプト一覧。 */
  final List<String> prompts = new CopyOnWriteArrayList<>();

  /** 検証に使う保持の指定。 */
  volatile boolean hold;

  /** 検証に使うキャンセル通知時応答。 */
  volatile boolean cancelOnResponse;

  /** 検証に使う取得元。 */
  SQLiteDataSource source;

  /** 検証ごとに状態を分離し、対象を操作できる環境を用意する。 */
  void setup(boolean failA) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("project")),
        config = Files.createDirectory(temporary.resolve("config"));
    source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + temporary.resolve("children.db"));
    repository = new DurableSubAgentRepository(source, Clock.systemUTC());
    properties = new SubAgentProperties();
    properties.setDurableEnabled(true);
    properties.setDagEnabled(true);
    var policy = new SubAgentToolPolicy(Set.of());
    Files.writeString(
        config.resolve("reviewer.yaml"),
        SubAgentConfigurationTest.yaml("reviewer").replace("tools: [readMultiFile]", "tools: []"));
    registry = new SubAgentRegistry(config, new SubAgentDefinitionLoader(policy, model -> true));
    assertTrue(registry.reload().isEmpty());
    ChatModel model =
        new ChatModel() {
          /** 検証用に、補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new AssertionError("stream required");
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            String input = prompt.getUserMessage().getText();
            prompts.add(input);
            String status = failA && input.startsWith("inspect a") ? "FAILURE" : "SUCCESS";
            if (hold) return Flux.never();
            if (cancelOnResponse) run.cancel();
            String output =
                "{\"status\":\""
                    + status
                    + "\",\"summary\":\"observed\",\"result\":{\"answer\":\"evidence\"},\"warnings\":[]}";
            return Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));
          }
        };
    var cancellation = new CommandCancellationService();
    var events = new AgentEventFactory(Clock.systemUTC());
    runner =
        new SubAgentRunner(
            registry,
            policy,
            id -> model,
            id -> ToolCallingChatOptions.builder().build(),
            List::of,
            cancellation,
            events,
            event -> {},
            Clock.systemUTC());
    runner.configureDurable(properties, repository);
    parallel = new ParallelSubAgentDelegator(runner, registry, cancellation);
    service =
        new SubAgentDagService(
            properties, repository, runner, registry, parallel, Clock.systemUTC());
    parent = new AgentRunContext("parent", "session", root, "project");
    run =
        new RunExecutionContext(
            "parent", new OutputLimitRunBudget(0, 8), null, events, event -> {});
    run.setRunContext(parent);
    run.setUserRequest("delegate a bounded child graph");
  }

  /** {@code spec} の条件を再現するための補助情報を返す。 */
  SubAgentDagSpec spec(SubAgentDagSpec.FailurePolicy policy) {
    return new SubAgentDagSpec(
        List.of(
            new SubAgentDagSpec.Node("a", "reviewer", "inspect a", null, List.of()),
            new SubAgentDagSpec.Node("b", "reviewer", "inspect b", null, List.of()),
            new SubAgentDagSpec.Node(
                "compare", "reviewer", "compare evidence", null, List.of("a", "b"))),
        policy,
        2);
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    if (parallel != null) parallel.close();
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void dependencyResultsReachFanInWithHashesAndSharedDurableBudget() throws Exception {
    setup(false);
    var outcome = service.submit(run, spec(SubAgentDagSpec.FailurePolicy.FAIL_FAST));
    assertEquals("COMPLETED", outcome.status());
    assertEquals(3, prompts.size());
    String compare =
        prompts.stream()
            .filter(value -> value.startsWith("compare evidence"))
            .findFirst()
            .orElseThrow();
    assertTrue(compare.contains("resultHash"));
    assertTrue(compare.contains("childId"));
    var graph = repository.get(parent, outcome.graphId());
    assertEquals(3, graph.consumedCalls());
    assertEquals(5, run.sharedLlmReservation().remaining());
    assertEquals(3, outcome.nodes().size());
    assertTrue(
        outcome.nodes().stream()
            .allMatch(node -> node.resultHash() != null && node.resultHash().length() == 64));
    var restored =
        new SubAgentDagService(
            properties, repository, runner, registry, parallel, Clock.systemUTC());
    assertEquals(outcome, restored.get(parent, outcome.graphId()));
    assertEquals(3, prompts.size());
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void partialContinuationRunsIndependentBranchAndBlocksDependentFanIn() throws Exception {
    setup(true);
    var outcome = service.submit(run, spec(SubAgentDagSpec.FailurePolicy.CONTINUE_INDEPENDENT));
    assertEquals("PARTIAL", outcome.status());
    assertEquals(2, prompts.size());
    assertTrue(prompts.stream().noneMatch(value -> value.startsWith("compare evidence")));
    assertEquals(
        "BLOCKED",
        outcome.nodes().stream()
            .filter(node -> node.id().equals("compare"))
            .findFirst()
            .orElseThrow()
            .status());
    assertEquals(2, repository.get(parent, outcome.graphId()).consumedCalls());
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void timeoutCanResumeRemainingChildrenWithoutResettingOriginalBudget() throws Exception {
    setup(false);
    hold = true;
    properties.setDagTimeout(Duration.ofSeconds(1));
    var outcome = service.submit(run, spec(SubAgentDagSpec.FailurePolicy.CONTINUE_INDEPENDENT));
    assertTrue(Set.of("TIMEOUT", "UNKNOWN").contains(outcome.status()));
    long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (service.get(parent, outcome.graphId()).nodes().stream()
            .anyMatch(n -> n.status().equals("RUNNING"))
        && System.nanoTime() < until) Thread.sleep(10);
    int consumed = repository.get(parent, outcome.graphId()).consumedCalls();
    assertEquals(2, consumed);
    hold = false;
    var saved = repository.get(parent, outcome.graphId());
    repository = new DurableSubAgentRepository(source, Clock.systemUTC());
    runner.configureDurable(properties, repository);
    service =
        new SubAgentDagService(
            properties, repository, runner, registry, parallel, Clock.systemUTC());
    run.setUserRequest("subagent graph resume " + saved.id() + " " + saved.revision());
    var resumed = service.resume(run, saved.id(), saved.revision());
    assertEquals("COMPLETED", resumed.status());
    assertEquals(5, repository.get(parent, saved.id()).consumedCalls());
    assertEquals(3, run.sharedLlmReservation().remaining());
    assertThrows(
        IllegalArgumentException.class, () -> service.resume(run, saved.id(), saved.revision()));
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void ownershipAndExactCurrentHumanResumeAreRequired() throws Exception {
    setup(true);
    var outcome = service.submit(run, spec(SubAgentDagSpec.FailurePolicy.CONTINUE_INDEPENDENT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.get(
                new AgentRunContext("other", "another-session", parent.projectRoot(), "project"),
                outcome.graphId()));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.resume(run, outcome.graphId(), outcome.revision()));
    assertEquals(2, prompts.size());
  }

  /** 補助エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void anotherGraphsPlanCannotSubstituteItsChildren() throws Exception {
    setup(false);
    var first = service.submit(run, spec(SubAgentDagSpec.FailurePolicy.CONTINUE_INDEPENDENT));
    var second = service.submit(run, spec(SubAgentDagSpec.FailurePolicy.CONTINUE_INDEPENDENT));
    var db = org.springframework.jdbc.core.simple.JdbcClient.create(source);
    db.sql("UPDATE subagent_checkpoints SET snapshot=json_set(snapshot,'$.context',?) WHERE id=?")
        .params(repository.get(parent, second.graphId()).context(), first.graphId())
        .update();
    assertThrows(IllegalArgumentException.class, () -> service.get(parent, first.graphId()));
  }

  /** 補助エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelledParentLeavesInspectableGraphWithoutDispatchingRemainingWaves() throws Exception {
    setup(false);
    hold = true;
    properties.setDagTimeout(Duration.ofSeconds(1));
    var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var future =
          worker.submit(
              () -> service.submit(run, spec(SubAgentDagSpec.FailurePolicy.CONTINUE_INDEPENDENT)));
      long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (prompts.isEmpty() && System.nanoTime() < until) Thread.sleep(10);
      run.cancel();
      var outcome = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(Set.of("CANCELLED", "UNKNOWN", "TIMEOUT").contains(outcome.status()));
      assertTrue(prompts.size() <= 2);
      assertNotNull(service.get(parent, outcome.graphId()));
    } finally {
      worker.shutdownNow();
    }
  }

  /** 補助エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationBetweenWavesReturnsDurableUnknownReceipt() throws Exception {
    setup(false);
    cancelOnResponse = true;
    var outcome = service.submit(run, spec(SubAgentDagSpec.FailurePolicy.CONTINUE_INDEPENDENT));
    assertEquals("UNKNOWN", outcome.status());
    assertTrue(prompts.size() <= 2);
    assertEquals(outcome.graphId(), service.get(parent, outcome.graphId()).graphId());
  }
}
