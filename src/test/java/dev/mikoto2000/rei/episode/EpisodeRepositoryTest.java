package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 経験の記録保存と検索の窓口の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class EpisodeRepositoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う保存と検索の窓口。 */
  EpisodeRepository repository;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    repository =
        new EpisodeRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db")));
  }

  /** {@code episode} の条件を再現するための補助情報を返す。 */
  Episode episode(String revision, Episode.Status status) {
    return new Episode(
        "e1",
        "p1",
        "s1",
        revision,
        "2026-10-11T00:00:00Z",
        status,
        "CLI design",
        "Separate process",
        List.of(
            new Episode.Claim("reason", "Isolation", Episode.Evidence.USER_EXPLICIT, "r1", "user"),
            new Episode.Claim(
                "alternative", "In process", Episode.Evidence.MODEL_PROPOSAL, "r1", "assistant"),
            new Episode.Claim(
                "unknown", "Performance", Episode.Evidence.UNVERIFIED, "r1", "assistant")),
        .8);
  }

  /** 過去の経験の活用で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void persistsClaimsAndImmutableRevisionsAcrossRestart() {
    repository.saveBatch("s1", 0, 1, List.of(episode("v1", Episode.Status.IN_PROGRESS)));
    repository.saveBatch("s1", 1, 2, List.of(episode("v2", Episode.Status.WITHDRAWN)));
    var restarted =
        new EpisodeRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db")));
    assertEquals(2, restarted.revisions("e1", "p1").size());
    assertEquals(
        Episode.Evidence.MODEL_PROPOSAL,
        restarted.find("e1", "p1").orElseThrow().claims().get(1).evidence());
    assertEquals(Episode.Status.WITHDRAWN, restarted.find("e1", "p1").orElseThrow().status());
    assertTrue(restarted.find("e1", "p2").isEmpty());
  }

  /** 過去の経験の活用で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void checkpointCompareAndSetIsAtomicAndRetryDoesNotDuplicate() {
    repository.saveBatch("s1", 0, 1, List.of(episode("v1", Episode.Status.COMPLETED)));
    assertThrows(
        IllegalStateException.class,
        () -> repository.saveBatch("s1", 0, 1, List.of(episode("v1", Episode.Status.COMPLETED))));
    assertEquals(1, repository.revisions("e1", "p1").size());
    assertEquals(1, repository.checkpoint("s1"));
  }

  /** 過去の経験の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void lexicalSearchIsBoundedAndProjectScoped() {
    repository.saveBatch("s1", 0, 1, List.of(episode("v1", Episode.Status.COMPLETED)));
    assertEquals(1, repository.search("Isolation", "p1", 5).size());
    assertTrue(repository.search("Isolation", "p2", 5).isEmpty());
    assertTrue(repository.search("\" OR *", "p1", 5).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> repository.search("CLI", "p1", 101));
  }

  /** 過去の経験の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void emptyBatchAdvancesCheckpoint() {
    repository.saveBatch("s1", 0, 3, List.of());
    assertEquals(3, repository.checkpoint("s1"));
  }

  /** 過去の経験の活用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failureRollsBackEpisodeAndCheckpointTogether() {
    var valid = episode("v1", Episode.Status.COMPLETED);
    var foreign =
        new Episode(
            "e2",
            "p1",
            "other",
            "v2",
            valid.occurredAt(),
            valid.status(),
            valid.title(),
            valid.summary(),
            valid.claims(),
            .8);
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.saveBatch("s1", 0, 2, List.of(valid, foreign)));
    assertEquals(0, repository.checkpoint("s1"));
    assertTrue(repository.find("e1", "p1").isEmpty());
    repository.saveBatch("s1", 0, 1, List.of(valid));
    assertEquals(1, repository.checkpoint("s1"));
  }

  /** 過去の経験の活用で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void sourceLeasePreventsConcurrentModelProcessingAndIsReleased() {
    assertTrue(repository.acquire("s1", "worker1"));
    assertFalse(repository.acquire("s1", "worker2"));
    repository.release("s1", "worker2");
    assertFalse(repository.acquire("s1", "worker2"));
    repository.release("s1", "worker1");
    assertTrue(repository.acquire("s1", "worker2"));
  }

  /** 判断結果リンク一覧について、既存の同一プロジェクト記憶を必要とすることを確認する。 */
  @Test
  void decisionLinksRequireExistingSameProjectMemory() {
    var db =
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db")));
    db.sql("CREATE TABLE memories(id TEXT PRIMARY KEY,project_id TEXT,type TEXT,scope TEXT)")
        .update();
    db.sql("INSERT INTO memories VALUES('m','p1','DECISION','PROJECT')").update();
    repository.saveBatch("s1", 0, 1, List.of(episode("v1", Episode.Status.COMPLETED)));
    repository.linkMemory("p1", "s1", List.of("r1"), "m");
    assertEquals("m", repository.relations("e1", "p1").getFirst().get("targetId"));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.linkMemory("p2", "s1", List.of("r1"), "m"));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.linkMemory("p1", "s1", List.of("r1"), "invented"));
  }

  /** 過去の経験の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void linksActualWorkContextItemsByTheirSourceRuns() {
    repository.saveBatch("s1", 0, 1, List.of(episode("v1", Episode.Status.COMPLETED)));
    var evidence =
        new dev.mikoto2000.rei.workcontext.WorkContext.Evidence(
            "source",
            dev.mikoto2000.rei.workcontext.WorkContext.Origin.USER,
            "s1",
            "r1",
            "r1",
            null,
            null,
            null,
            java.time.Instant.EPOCH,
            java.time.Instant.EPOCH,
            "CLI");
    var item =
        new dev.mikoto2000.rei.workcontext.WorkContext.Item(
            "w1",
            dev.mikoto2000.rei.workcontext.WorkContext.Kind.CURRENT_WORK,
            "CLI",
            "",
            dev.mikoto2000.rei.workcontext.WorkContext.Status.OPEN,
            List.of(evidence),
            java.time.Instant.EPOCH,
            java.time.Instant.EPOCH,
            false,
            null);
    var context =
        new dev.mikoto2000.rei.workcontext.WorkContext(
            "p1",
            1,
            java.time.Instant.EPOCH,
            java.time.Instant.EPOCH,
            null,
            List.of(item),
            java.util.Set.of("r1"));
    repository.linkWorkContext(context, "s1");
    assertEquals("w1", repository.relations("e1", "p1").getFirst().get("targetId"));
    assertEquals("WORK_CONTEXT", repository.relations("e1", "p1").getFirst().get("kind"));
    assertTrue(repository.relations("e1", "p2").isEmpty());
  }
}
