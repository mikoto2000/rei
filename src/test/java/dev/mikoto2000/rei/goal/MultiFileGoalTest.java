package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 複数ファイル目標の結果と状態の契約を検証するテスト。 */
class MultiFileGoalTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う保存と検索の窓口。 */
  GoalRepository repository;

  /** {@code hash} の条件を再現するための補助情報を返す。 */
  String hash(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner() {
    return new AgentRunContext("run", "session", root, "project");
  }

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    repository =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("goals.db")),
            Clock.systemUTC());
  }

  /** 各対象ファイルを必要とすることを確認する。 */
  @Test
  void persistsAllCriteriaAndRequiresEveryFileAfterRestart() throws Exception {
    var criteria =
        List.of(
            new GoalRepository.FileCriterion("A.txt", hash("a")),
            new GoalRepository.FileCriterion("B.txt", hash("b")));
    var goal = repository.create(owner(), "two artifacts", criteria, 3, 10);
    Files.writeString(root.resolve("A.txt"), "a");
    assertFalse(new FileGoalVerifier().verify(goal).satisfied());
    Files.writeString(root.resolve("B.txt"), "b");
    var restarted =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("goals.db")),
            Clock.systemUTC());
    var saved = restarted.get("project", goal.id());
    assertEquals(criteria, saved.criteria());
    assertTrue(new FileGoalVerifier().verify(saved).satisfied());
    Files.writeString(root.resolve("B.txt"), "changed");
    assertFalse(new FileGoalVerifier().verify(saved).satisfied());
  }

  /** 目標の達成管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void invalidDuplicateOrUnboundedCriteriaLeaveNoGoal() throws Exception {
    var valid = new GoalRepository.FileCriterion("A.txt", hash("a"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.create(
                owner(),
                "x",
                List.of(valid, new GoalRepository.FileCriterion("./A.txt", hash("b"))),
                3,
                10));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.create(
                owner(),
                "x",
                List.of(valid, new GoalRepository.FileCriterion("../escape", hash("b"))),
                3,
                10));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.create(owner(), "x", Collections.nCopies(17, valid), 3, 10));
    assertThrows(
        IllegalArgumentException.class, () -> repository.create(owner(), "x", List.of(), 3, 10));
    assertTrue(repository.list("project").isEmpty());
  }

  /** 目標の達成管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void legacySingleFileCriteriaAndBudgetBehaviorRemainCompatible() throws Exception {
    var goal = repository.create(owner(), "x", "A.txt", hash("a"), 3, 1);
    assertEquals(List.of(new GoalRepository.FileCriterion("A.txt", hash("a"))), goal.criteria());
    var claim = repository.claim("project", goal.id());
    assertTrue(repository.reserveLlm(claim));
    assertFalse(repository.reserveLlm(claim));
  }

  /** 振り返りについて、各対象期待する内容ファイルを保持することを確認する。 */
  @Test
  void reflectionPreservesEveryExpectedFileAndVerificationFacts() throws Exception {
    var criteria =
        List.of(
            new GoalRepository.FileCriterion("A.txt", hash("a")),
            new GoalRepository.FileCriterion("B.txt", hash("b")));
    var goal = repository.create(owner(), "both", criteria, 3, 10);
    repository.verifiedWithoutRun("project", goal.id());
    var records =
        new dev.mikoto2000.rei.reflection.GoalReflectionRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("goals.db")),
            Clock.systemUTC());
    var service =
        new dev.mikoto2000.rei.reflection.GoalReflectionService(
            repository,
            records,
            org.mockito.Mockito.mock(dev.mikoto2000.rei.event.AgentEventBus.class),
            Clock.systemUTC());
    var item = service.collect("project", goal.id());
    assertEquals("VERIFIED", item.actual());
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    assertEquals(List.of("A.txt", "B.txt"), mapper.readValue(item.expectedFile(), List.class));
    assertEquals(
        List.of(hash("a"), hash("b")), mapper.readValue(item.expectedSha256(), List.class));
  }
}
