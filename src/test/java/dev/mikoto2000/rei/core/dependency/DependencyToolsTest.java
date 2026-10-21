package dev.mikoto2000.rei.core.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 依存条件ツール一覧の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class DependencyToolsTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使うプロジェクト。 */
  final String project = UUID.randomUUID().toString();

  /** 検証に使うリポジトリ。 */
  PersistentDependencyRepository repo;

  /** 検証に使うサービス。 */
  DependencyObservationService service;

  /** 検証に使う取得元。 */
  DependencySourceProbe source;

  /** 検証に使うツール一覧。 */
  DependencyTools tools;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    repo =
        new PersistentDependencyRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("deps.db")),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    source = mock(DependencySourceProbe.class);
    when(source.probe(any()))
        .thenAnswer(
            invocation -> {
              var entry = invocation.getArgument(0, PersistentDependencyRepository.Entry.class);
              return new DependencyObservation(
                  entry.id(), DependencyState.WAITING, "user_answer_waiting");
            });
    service =
        new DependencyObservationService(
            repo,
            source,
            new DependencyWatcherProperties(false),
            new ToolPermissionPolicy(new ToolPermissionProperties(true, null, null, null)),
            e -> {});
    tools = new DependencyTools(repo, service, source);
  }

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner() {
    return new AgentRunContext("r", "s", dir, project);
  }

  /** 外部条件の待機で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void registrationIsScopedAndReadInspectionCannotReachNetwork() {
    assertThrows(
        IllegalArgumentException.class,
        () -> tools.registerDependency("FILE_EXISTS", "file", null, null, null));
    String id;
    try (var scope = AgentRunScope.open(owner())) {
      id = tools.registerDependency("HTTP_STATUS", "https://example.com", "200", "PT1H", null).id();
      assertThrows(IllegalArgumentException.class, () -> tools.checkDependency(id));
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(source);
      assertEquals(DependencyState.WAITING, tools.checkHttpDependency(id).state());
    }
    try (var scope = AgentRunScope.open(new AgentRunContext("r", "other", dir, project))) {
      assertThrows(IllegalArgumentException.class, () -> tools.dependencyStatus(id));
    }
  }

  /** 停滞状態を保持することを確認する。 */
  @Test
  void callbackHidesContextAndWaitingPreservesStagnationState() throws Exception {
    var execution =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            "r",
            new dev.mikoto2000.rei.llm.OutputLimitRunBudget(1, 10),
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(dir),
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            e -> {});
    var callbacks =
        org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build()
            .getToolCallbacks();
    var callback =
        Arrays.stream(callbacks)
            .filter(c -> c.getToolDefinition().name().equals("waitForDependency"))
            .findFirst()
            .orElseThrow();
    assertFalse(callback.getToolDefinition().inputSchema().contains("toolContext"));
    try (var scope = AgentRunScope.open(owner())) {
      String id = tools.registerDependency("USER_ANSWER", "Proceed?", null, null, null).id();
      execution.beginIteration();
      var before = execution.evaluator().beforeTool("waitForDependency", "{}");
      var result =
          callback.call(
              "{\"dependencyId\":\"" + id + "\",\"timeoutSeconds\":0}",
              new org.springframework.ai.chat.model.ToolContext(
                  Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, execution)));
      assertTrue(result.contains("WAITING"));
      execution.recordTool("waitForDependency", "{}", result, before);
      execution.endIteration();
      assertEquals(0, execution.detector().stagnationCount());
      tools.cancelDependency(id);
      assertEquals(DependencyState.CANCELLED, tools.dependencyStatus(id).state());
    }
    assertFalse(
        Arrays.stream(callbacks)
            .anyMatch(
                c -> c.getToolDefinition().name().toLowerCase(Locale.ROOT).contains("answer")));
  }

  /** 外部条件の待機で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void shellHumanAnswerCompletesOnlyItsOwnedProject() {
    PersistentDependencyRepository.Entry entry;
    try (var scope = AgentRunScope.open(owner())) {
      entry = tools.registerDependency("USER_ANSWER", "Proceed?", null, null, null);
    }
    doAnswer(
            invocation -> {
              var e = invocation.getArgument(0, PersistentDependencyRepository.Entry.class);
              return new DependencyObservation(
                  e.id(),
                  e.answer() == null ? DependencyState.WAITING : DependencyState.COMPLETED,
                  e.answer() == null ? "user_answer_waiting" : "user_answer_received");
            })
        .when(source)
        .probe(any());
    var projects = mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    when(projects.currentContext())
        .thenReturn(new dev.mikoto2000.rei.core.project.ProjectContext(project, "p", dir));
    var command = new DependencyCommand(repo, service, projects, mock(ToolPermissionGuard.class));
    command.setShellOutput(new java.io.PrintWriter(new java.io.StringWriter()));
    assertEquals(
        0, new picocli.CommandLine(command).execute("answer", entry.id(), "--value", "Yes"));
    assertEquals(DependencyState.COMPLETED, repo.get(project, entry.id()).state());
    when(projects.currentContext())
        .thenReturn(
            new dev.mikoto2000.rei.core.project.ProjectContext(
                UUID.randomUUID().toString(), "other", dir));
    assertEquals(2, new picocli.CommandLine(command).execute("show", entry.id()));
  }
}
