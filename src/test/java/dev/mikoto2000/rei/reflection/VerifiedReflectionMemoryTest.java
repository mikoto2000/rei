package dev.mikoto2000.rei.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.GoalLifecyclePayload;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.goal.FileGoalVerifier;
import dev.mikoto2000.rei.goal.GoalLoopService;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.MemoryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 確認済み振り返り記憶の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class VerifiedReflectionMemoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  Clock clock = Clock.fixed(Instant.parse("2026-10-06T14:00:00Z"), ZoneOffset.UTC);

  /** 検証に使うprops。 */
  MemoryProperties props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null);

  /** 検証に使う取得元。 */
  DriverManagerDataSource source;

  /** 検証に使う目標一覧。 */
  GoalRepository goals;

  /** 検証に使う振り返り一覧。 */
  GoalReflectionRepository reflections;

  /** 検証に使う収集。 */
  GoalReflectionService collect;

  /** 検証に使う記憶一覧。 */
  MemoryRepository memories;

  /** 検証に使う受付窓口。 */
  GoalLoopService.Gateway gateway;

  /** 検証に使うサービス。 */
  VerifiedReflectionMemoryService service;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    source = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("proof.db"));
    goals = new GoalRepository(source, clock);
    reflections = new GoalReflectionRepository(source, clock);
    collect = new GoalReflectionService(goals, reflections, new InMemoryAgentEventBus(), clock);
    memories = new MemoryRepository(source, new MemoryService(source, props, clock));
    gateway = mock(GoalLoopService.Gateway.class);
    service =
        new VerifiedReflectionMemoryService(
            goals, reflections, memories, new FileGoalVerifier(), gateway, props, clock);
  }

  /** {@code completed} の条件を再現するための補助情報を返す。 */
  GoalRepository.Goal completed() throws Exception {
    Files.writeString(dir.resolve("out.txt"), "verified content");
    String digest = new FileGoalVerifier().fingerprint(dir.toRealPath(), "out.txt").sha256();
    var goal =
        goals.create(
            new AgentRunContext("source", "session", dir.toRealPath(), "project"),
            "secret model objective",
            "out.txt",
            digest,
            3,
            5);
    return goals.verifiedWithoutRun("project", goal.id());
  }

  /** 作業の振り返りで、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void independentlyVerifiedFactHasPersistentProofAndNoFabricatedConversationTurn()
      throws Exception {
    var goal = completed();
    var reflection = collect.collect("project", goal.id());
    var promotion = service.promote("project", reflection.id());
    var memory = promotion.memory();
    assertEquals(MemoryScope.PROJECT, memory.scope());
    assertEquals(MemoryType.PROJECT_STATE, memory.type());
    assertEquals("project", memory.projectId());
    assertTrue(memory.content().contains("2026-10-06T14:00:00Z"));
    assertTrue(memory.content().contains(goal.sha256()));
    assertFalse(memory.content().contains("secret model objective"));
    assertTrue(memory.sources().isEmpty());
    assertEquals(reflection.id(), promotion.proof().reflectionId());
    assertEquals(goal.id(), promotion.proof().goalId());
    assertEquals("session", promotion.proof().sessionId());
    assertEquals(memory.id(), promotion.proof().memoryId());
    assertEquals(64, promotion.proof().criteriaSha256().length());
    var reopened = new MemoryRepository(source, new MemoryService(source, props, clock));
    assertEquals(
        promotion.proof(),
        reopened.verifiedReflectionProof("project", reflection.id()).orElseThrow());
    assertEquals(1, reopened.search("out.txt", "project", 10).size());
    assertTrue(reopened.search("out.txt", "other", 10).isEmpty());
    verify(gateway).validate(goal);
    verify(gateway, never()).dispatch(any(), anyString(), any());
    assertEquals(0, goals.get("project", goal.id()).attempts());
  }

  /** 作業の振り返りで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void changedArtifactUnverifiedOrForeignReflectionCannotBecomeKnowledge() throws Exception {
    var goal = completed();
    var reflection = collect.collect("project", goal.id());
    Files.writeString(dir.resolve("out.txt"), "changed");
    assertThrows(IllegalStateException.class, () -> service.promote("project", reflection.id()));
    assertTrue(memories.list("project", 10, 0).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> service.promote("other", reflection.id()));
    var failed =
        goals.create(
            new AgentRunContext("run", "session", dir, "project"),
            "failed",
            "missing.txt",
            "a".repeat(64),
            1,
            1);
    var claim = goals.claim("project", failed.id());
    goals.stop(claim, "BLOCKED", "budget_exhausted");
    var unknown = collect.collect("project", failed.id());
    assertThrows(IllegalArgumentException.class, () -> service.promote("project", unknown.id()));
    assertTrue(memories.list("project", 10, 0).isEmpty());
  }

  /** 作業の振り返りで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void repeatedPromotionKeepsHistoricalFactAndNeverResurrectsArchivedMemory() throws Exception {
    var goal = completed();
    var reflection = collect.collect("project", goal.id());
    var first = service.promote("project", reflection.id());
    Files.writeString(dir.resolve("out.txt"), "later edit");
    assertEquals(first, service.promote("project", reflection.id()));
    assertEquals(1, memories.list("project", 10, 0).size());
    memories.archive(first.memory().id());
    var repeated = service.promote("project", reflection.id());
    assertEquals(MemoryStatus.ARCHIVED, repeated.memory().status());
    assertTrue(memories.list("project", 10, 0).isEmpty());
  }

  /** 作業の振り返りで、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void jsonCriteriaAndMultipleFilesAreVerifiedBeforePromotion() throws Exception {
    Files.writeString(dir.resolve("result.json"), "{\"ok\":true,\"count\":1}");
    Files.writeString(dir.resolve("out.txt"), "content");
    var criteria =
        List.of(
            new GoalRepository.FileCriterion("result.json", "", "/ok", "true"),
            new GoalRepository.FileCriterion("result.json", "", "/count", "1.0"),
            new GoalRepository.FileCriterion(
                "out.txt",
                new FileGoalVerifier().fingerprint(dir.toRealPath(), "out.txt").sha256()));
    var goal =
        goals.create(
            new AgentRunContext("run", "session", dir.toRealPath(), "project"),
            "JSON",
            criteria,
            1,
            2);
    goals.verifiedWithoutRun("project", goal.id());
    var reflection = collect.collect("project", goal.id());
    var promoted = service.promote("project", reflection.id());
    assertTrue(promoted.memory().content().contains("/ok"));
    assertTrue(promoted.memory().content().contains("/count"));
    assertTrue(promoted.memory().content().contains("out.txt"));
  }

  /** 作業の振り返りの中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void memoryDisableOwnershipValidationAndCancellationCannotWriteFacts() throws Exception {
    var goal = completed();
    var reflection = collect.collect("project", goal.id());
    var disabled =
        new VerifiedReflectionMemoryService(
            goals,
            reflections,
            memories,
            new FileGoalVerifier(),
            gateway,
            new MemoryProperties(false, 20, 80, 10, 3, 2000, 60, null),
            clock);
    assertThrows(IllegalStateException.class, () -> disabled.promote("project", reflection.id()));
    doThrow(new IllegalArgumentException("owner unavailable")).when(gateway).validate(any());
    assertThrows(IllegalArgumentException.class, () -> service.promote("project", reflection.id()));
    reset(gateway);
    try {
      Thread.currentThread().interrupt();
      assertThrows(IllegalStateException.class, () -> service.promote("project", reflection.id()));
    } finally {
      Thread.interrupted();
    }
    assertTrue(memories.list("project", 10, 0).isEmpty());
  }

  /** 作業の振り返りで、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void concurrentPromotionPublishesOnlyOneMemoryAndProof() throws Exception {
    var goal = completed();
    var reflection = collect.collect("project", goal.id());
    var gate = new java.util.concurrent.CountDownLatch(1);
    var ids = new HashSet<String>();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var futures =
          new ArrayList<java.util.concurrent.Future<VerifiedReflectionMemoryService.Promotion>>();
      for (int i = 0; i < 4; i++)
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  return service.promote("project", reflection.id());
                }));
      gate.countDown();
      for (var future : futures) ids.add(future.get().memory().id());
    }
    assertEquals(1, ids.size());
    assertEquals(1, memories.list("project", 10, 0).size());
    assertTrue(memories.verifiedReflectionProof("project", reflection.id()).isPresent());
  }

  /** 作業の振り返りで、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void insertionFailureRollsBackProofAndAllowsALaterExplicitRetry() throws Exception {
    var goal = completed();
    var reflection = collect.collect("project", goal.id());
    var db = org.springframework.jdbc.core.simple.JdbcClient.create(source);
    db.sql(
            "CREATE TRIGGER fixture_reject_memory BEFORE INSERT ON memories BEGIN SELECT RAISE(ABORT,'fixture'); END")
        .update();
    assertThrows(RuntimeException.class, () -> service.promote("project", reflection.id()));
    assertTrue(memories.verifiedReflectionProof("project", reflection.id()).isEmpty());
    assertTrue(memories.list("project", 10, 0).isEmpty());
    db.sql("DROP TRIGGER fixture_reject_memory").update();
    assertNotNull(service.promote("project", reflection.id()).memory());
  }

  /** 作業の振り返りで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void forgedCompletionAndChangedSavedCriteriaCannotSupplyPromotionEvidence() throws Exception {
    Files.writeString(dir.resolve("out.txt"), "exists");
    var ready =
        goals.create(
            new AgentRunContext("run", "session", dir, "project"),
            "objective",
            "out.txt",
            "a".repeat(64),
            1,
            1);
    var snapshot =
        new GoalLifecyclePayload(ready.id(), "COMPLETED", 0, 1, 0, 1, "file_digest_verified");
    var forged =
        reflections.save(
            ready,
            new AgentEvent(
                "forged",
                0,
                clock.instant(),
                AgentEventType.GOAL_UPDATED,
                1,
                "session",
                null,
                null,
                null,
                null,
                snapshot,
                "project"),
            snapshot,
            "VERIFIED",
            "file_digest_verified",
            "CRITERION_SATISFIED",
            "model lesson");
    assertThrows(IllegalArgumentException.class, () -> service.promote("project", forged.id()));
    var goal = completed();
    var reflection = collect.collect("project", goal.id());
    org.springframework.jdbc.core.simple.JdbcClient.create(source)
        .sql("UPDATE agent_goals SET digest=? WHERE id=?")
        .params("b".repeat(64), goal.id())
        .update();
    assertThrows(IllegalArgumentException.class, () -> service.promote("project", reflection.id()));
    assertTrue(memories.list("project", 10, 0).isEmpty());
  }

  /** 作業の振り返りで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void shellPromotesExplicitlyAndMemoryShowDistinguishesReflectionProofFromTurns()
      throws Exception {
    var goal = completed();
    var reflection = collect.collect("project", goal.id());
    var projects = mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var project = mock(dev.mikoto2000.rei.core.project.ProjectContext.class);
    when(project.id()).thenReturn("project");
    when(projects.currentContext()).thenReturn(project);
    var command = new ReflectionCommand(reflections, collect, projects);
    command.setPromotion(service);
    var text = new java.io.StringWriter();
    command.setShellOutput(new java.io.PrintWriter(text));
    var cli = new picocli.CommandLine(command);
    assertEquals(0, cli.execute("promote", reflection.id()));
    var proof = memories.verifiedReflectionProof("project", reflection.id()).orElseThrow();
    var support =
        new dev.mikoto2000.rei.memory.command.MemoryCommandSupport(memories, null, projects, props);
    String shown = support.show(proof.memoryId());
    assertTrue(shown.contains("source sessions/turns: []"));
    assertTrue(shown.contains("verified reflection proof:"));
    assertTrue(shown.contains(reflection.id()));
    when(project.id()).thenReturn("other");
    assertEquals(2, cli.execute("promote", reflection.id()));
    assertThrows(IllegalArgumentException.class, () -> support.show(proof.memoryId()));
  }

  /** 作業の振り返りで、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void oversizedCriteriaFailWithoutTruncatingEvidenceOrSavingAPartialProof() throws Exception {
    var values = new LinkedHashMap<String, String>();
    var criteria = new ArrayList<GoalRepository.FileCriterion>();
    for (int i = 0; i < 6; i++) {
      values.put("value" + i, "x".repeat(700));
      criteria.add(
          new GoalRepository.FileCriterion(
              "large.json", "", "/value" + i, "\"" + "x".repeat(700) + "\""));
    }
    Files.writeString(
        dir.resolve("large.json"),
        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(values));
    var goal =
        goals.create(
            new AgentRunContext("run", "session", dir.toRealPath(), "project"),
            "large",
            criteria,
            1,
            1);
    goals.verifiedWithoutRun("project", goal.id());
    var reflection = collect.collect("project", goal.id());
    assertThrows(IllegalArgumentException.class, () -> service.promote("project", reflection.id()));
    assertTrue(memories.verifiedReflectionProof("project", reflection.id()).isEmpty());
    assertTrue(memories.list("project", 10, 0).isEmpty());
  }
}
