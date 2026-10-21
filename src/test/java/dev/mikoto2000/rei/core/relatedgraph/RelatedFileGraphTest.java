package dev.mikoto2000.rei.core.relatedgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 関連ファイル関連図の結果と状態の契約を検証するテスト。 */
class RelatedFileGraphTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** {@code graph} の条件を再現するための補助情報を返す。 */
  private RelatedFileGraph graph(int maxRelations, Instant start) {
    return new RelatedFileGraph(maxRelations, Clock.fixed(start, ZONE));
  }

  /** {@code at} の条件を再現するための補助情報を返す。 */
  private OffsetDateTime at(Instant instant) {
    return instant.atZone(ZONE).toOffsetDateTime();
  }

  /** 関連情報の探索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void relationCanBeAdded() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    assertFalse(graph.isEmpty());
    assertEquals(1, graph.size());
  }

  /** 関連情報の探索で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void relatedFilesCanBeRetrievedByPath() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.addRelation("src/UserServiceTest.java", "src/UserService.java", "TESTS", "SEARCH");

    List<FileRelation> related = graph.getRelated("src/UserService.java");
    assertEquals(2, related.size());
    assertTrue(related.stream().anyMatch(r -> r.sourcePath().equals("src/UserController.java")));
    assertTrue(related.stream().anyMatch(r -> r.sourcePath().equals("src/UserServiceTest.java")));
  }

  /** 関連情報の探索で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void sameRelationIsNotDuplicated() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    assertEquals(1, graph.size());
  }

  /** 関連情報の探索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void differentTypeIsDifferentRelation() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.addRelation("src/UserController.java", "src/UserService.java", "IMPORTS", "SEARCH");
    assertEquals(2, graph.size());
  }

  /** 取得元ファイル変更について、関連一覧を取り除くことを確認する。 */
  @Test
  void sourceFileChangeRemovesRelations() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.removeRelationsFor("src/UserController.java");
    assertTrue(graph.isEmpty());
  }

  /** 対象ファイル変更について、関連一覧を取り除くことを確認する。 */
  @Test
  void targetFileChangeRemovesRelations() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.removeRelationsFor("src/UserService.java");
    assertTrue(graph.isEmpty());
  }

  /** 取り除く全対象関連一覧を削除することを確認する。 */
  @Test
  void deleteRemovesAllRelationsForFile() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.addRelation("src/UserServiceTest.java", "src/UserService.java", "TESTS", "SEARCH");
    graph.removeRelationsFor("src/UserService.java");
    assertTrue(graph.isEmpty());
  }

  /** 関連情報の探索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void maxRelationsEvictsOldest() {
    Instant start = Instant.parse("2026-08-17T00:00:00Z");
    RelatedFileGraph graph = graph(2, start);
    graph.addRelation("a", "b", "REFERENCES", "SEARCH");
    graph.addRelation("c", "d", "REFERENCES", "SEARCH");
    graph.addRelation("e", "f", "REFERENCES", "SEARCH");
    assertEquals(2, graph.size());
    assertTrue(graph.getRelated("a").isEmpty());
    assertTrue(graph.getRelated("c").size() == 1);
    assertTrue(graph.getRelated("e").size() == 1);
  }

  /** 関連情報の探索で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void promptRendersOnlyRelationsForWorkingSetFiles() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.addRelation("src/Other.java", "src/Unrelated.java", "REFERENCES", "SEARCH");

    String prompt = graph.renderForPrompt(java.util.Set.of("src/UserController.java"));
    assertTrue(prompt.contains("src/UserController.java"));
    assertTrue(prompt.contains("src/UserService.java"));
    assertFalse(prompt.contains("src/Other.java"));
  }

  /** 関連情報の探索で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void emptyGraphRendersBlank() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    assertEquals("", graph.renderForPrompt(java.util.Set.of("src/UserController.java")));
  }

  /** 関連情報の探索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void lastConfirmedAtIsUpdatedOnReconfirmation() {
    Instant start = Instant.parse("2026-08-17T00:00:00Z");
    RelatedFileGraph graph = graph(100, start);
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    FileRelation first = graph.relations().get(0);
    assertEquals(at(start), first.lastConfirmedAt());

    Instant later = Instant.parse("2026-08-17T01:00:00Z");
    RelatedFileGraph graph2 = graph(100, later);
    graph2.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    FileRelation second = graph2.relations().get(0);
    assertEquals(at(later), second.lastConfirmedAt());
    assertEquals(1, graph2.size());
  }
}
