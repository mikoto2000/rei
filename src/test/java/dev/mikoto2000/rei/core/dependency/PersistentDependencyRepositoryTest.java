package dev.mikoto2000.rei.core.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 保存される依存条件保存と検索の窓口の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class PersistentDependencyRepositoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Instant now = Instant.parse("2026-10-04T00:00:00Z");

  /** 検証で使用する保存と検索の窓口を、指定された条件に合わせて用意する。 */
  PersistentDependencyRepository repository(Instant time) {
    return new PersistentDependencyRepository(
        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("dependencies.db")),
        Clock.fixed(time, ZoneOffset.UTC));
  }

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner() {
    return new AgentRunContext("run", "session", dir, "project");
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  PersistentDependencyRepository.Entry create(
      PersistentDependencyRepository repo, List<String> parents) {
    return repo.create(
        owner(),
        new DependencySpec(DependencySpec.Kind.FILE_EXISTS, "result", null),
        Duration.ofHours(1),
        parents);
  }

  /** 永続化済み関連図復元するおよびについて、各対象親を必要とすることを確認する。 */
  @Test
  void persistedGraphRestoresAndRequiresEveryParent() {
    var repo = repository(now);
    var a = create(repo, List.of());
    var b = create(repo, List.of());
    var child = create(repo, List.of(a.id(), b.id()));
    var restarted = repository(now);
    assertEquals(DependencyState.BLOCKED, restarted.prepare("project", child.id()).state());
    restarted.observe(a, DependencyState.COMPLETED, "file_exists");
    assertEquals(DependencyState.BLOCKED, restarted.prepare("project", child.id()).state());
    restarted.observe(b, DependencyState.COMPLETED, "file_exists");
    assertEquals(DependencyState.WAITING, restarted.prepare("project", child.id()).state());
    assertEquals(List.of(a.id(), b.id()), restarted.get("project", child.id()).prerequisites());
    assertThrows(IllegalArgumentException.class, () -> repo.get("other", child.id()));
  }

  /** 外部条件の待機の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void staleObservationCancellationAndTimeoutCannotResurrectTerminalState() {
    var repo = repository(now);
    var entry = create(repo, List.of());
    repo.cancel("project", entry.id());
    assertEquals(
        DependencyState.CANCELLED, repo.observe(entry, DependencyState.COMPLETED, "stale").state());
    assertEquals(
        DependencyState.CANCELLED,
        repository(now.plusSeconds(7200)).prepare("project", entry.id()).state());
    var next = create(repo, List.of());
    assertEquals(
        DependencyState.FAILED,
        repository(now.plusSeconds(3600)).prepare("project", next.id()).state());
    assertEquals("dependency_deadline_expired", repo.get("project", next.id()).reason());
  }

  /** 外部条件の待機で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void parentsMustExistInSameProjectAndSession() {
    var repo = repository(now);
    var parent = create(repo, List.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repo.create(
                new AgentRunContext("run", "other-session", dir, "project"),
                new DependencySpec(DependencySpec.Kind.FILE_EXISTS, "x", null),
                Duration.ofHours(1),
                List.of(parent.id())));
    assertThrows(IllegalArgumentException.class, () -> create(repo, List.of("missing")));
    assertThrows(
        IllegalArgumentException.class, () -> create(repo, List.of(parent.id(), parent.id())));
    assertEquals(1, repo.list("project").size());
  }

  /** 外部条件の待機で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void factualOutboxIsDurableDeduplicatedAndAcknowledgedOnlyExplicitly() {
    var repo = repository(now);
    var entry = create(repo, List.of());
    int before = repo.pendingFacts().size();
    repo.observe(entry, entry.state(), entry.reason());
    assertEquals(before, repo.pendingFacts().size());
    repo.observe(entry, DependencyState.COMPLETED, "file_exists");
    var restarted = repository(now);
    var facts = restarted.pendingFacts();
    assertEquals(2, facts.size());
    assertNotEquals(facts.get(0).eventId(), facts.get(1).eventId());
    restarted.ackFact(facts.get(0).eventId());
    assertEquals(1, repo.pendingFacts().size());
  }

  /** 外部条件の待機で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void answerIsSeparateFromDependenciesAndCannotBeInjectedIntoAnotherKind() {
    var repo = repository(now);
    var parent = create(repo, List.of());
    var answer =
        repo.create(
            owner(),
            new DependencySpec(DependencySpec.Kind.USER_ANSWER, "Which option?", null),
            Duration.ofHours(1),
            List.of(parent.id()));
    repo.answer("project", answer.id(), "Choice A");
    assertEquals(DependencyState.BLOCKED, repo.prepare("project", answer.id()).state());
    assertEquals("Choice A", repo.get("project", answer.id()).answer());
    assertThrows(IllegalArgumentException.class, () -> repo.answer("project", parent.id(), "x"));
  }

  /** 外部条件の待機で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidSpecsAndLifetimeAreRejectedBeforeAnyRows() {
    var repo = repository(now);
    assertThrows(
        IllegalArgumentException.class,
        () -> new DependencySpec(DependencySpec.Kind.FILE_EXISTS, "../outside", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DependencySpec(DependencySpec.Kind.FILE_SHA256, "file", "bad"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DependencySpec(DependencySpec.Kind.HTTP_STATUS, "file:///secret", "200"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DependencySpec(
                DependencySpec.Kind.HTTP_STATUS, "https://user:pass@example.com", "200"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repo.create(
                owner(),
                new DependencySpec(DependencySpec.Kind.FILE_EXISTS, "file", null),
                Duration.ZERO,
                List.of()));
    assertTrue(repo.list("project").isEmpty());
  }
}
