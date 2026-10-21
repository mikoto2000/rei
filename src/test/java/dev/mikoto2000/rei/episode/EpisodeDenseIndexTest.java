package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.vectorstore.LazySqliteVectorStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 経験の記録ベクトルによる位置の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class EpisodeDenseIndexTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 過去の経験の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void anOlderWorkerCannotLeaveANewerRevisionMarkedIndexedAfterOverwritingItsVector() {
    var repo =
        new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("m.db")));
    repo.saveBatch("s", 0, 1, List.of(EpisodeRevisionPagingTest.episode("v1")));
    var vectors = mock(LazySqliteVectorStore.class);
    doAnswer(
            i -> {
              var newer = EpisodeRevisionPagingTest.episode("v2");
              repo.saveBatch("s", 1, 2, List.of(newer));
              repo.markDense(newer);
              return null;
            })
        .when(vectors)
        .replaceBySource(anyString(), anyString(), anyString(), anyList());
    new EpisodeDenseIndex(repo, vectors).indexPending("s", null);
    assertEquals(
        List.of("v2"), repo.pendingDense("s", 50).stream().map(Episode::revision).toList());
  }

  /** 過去の経験の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void embeddingInputIsBounded() {
    var repo =
        new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("m.db")));
    var claims =
        java.util.stream.IntStream.range(0, 4)
            .mapToObj(
                i ->
                    new Episode.Claim(
                        "reason",
                        "discussion ".repeat(170),
                        Episode.Evidence.MODEL_PROPOSAL,
                        "r",
                        "assistant"))
            .toList();
    repo.saveBatch(
        "s",
        0,
        1,
        List.of(
            new Episode(
                "e",
                "p",
                "s",
                "v",
                "2026-10-11T00:00:00Z",
                Episode.Status.UNVERIFIED,
                "design",
                "summary",
                claims,
                .5)));
    var vectors = mock(LazySqliteVectorStore.class);
    doAnswer(
            i -> {
              List<Document> docs = i.getArgument(3);
              assertTrue(docs.getFirst().getText().length() <= 4000);
              return null;
            })
        .when(vectors)
        .replaceBySource(anyString(), anyString(), anyString(), anyList());
    new EpisodeDenseIndex(repo, vectors).indexPending("s", null);
  }

  /** 過去の経験の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void rebuildingTheDerivedDatabaseRequeuesPreviouslyIndexedEpisodes() {
    var repo =
        new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("m.db")));
    var e = EpisodeRevisionPagingTest.episode("v1");
    repo.saveBatch("s", 0, 1, List.of(e));
    repo.markDense(e);
    assertTrue(repo.pendingDense("s", 50).isEmpty());
    assertEquals(1, repo.pendingDense("s", 50, "rebuilt-database").size());
    var vectors = mock(LazySqliteVectorStore.class);
    new EpisodeDenseIndex(repo, vectors, "rebuilt-database").indexPending("s", null);
    assertTrue(repo.pendingDense("s", 50, "rebuilt-database").isEmpty());
  }

  /** 過去の経験の活用で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void failedIndexingRemainsPendingAndRestartRetriesOnlyTheLatestRevision() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("m.db"));
    var repo = new EpisodeRepository(ds);
    repo.saveBatch("s", 0, 1, List.of(EpisodeRevisionPagingTest.episode("v1")));
    var vectors = mock(LazySqliteVectorStore.class);
    var index = new EpisodeDenseIndex(repo, vectors);
    doThrow(new IllegalStateException("provider unavailable"))
        .when(vectors)
        .replaceBySource(anyString(), anyString(), anyString(), anyList());
    assertThrows(IllegalStateException.class, () -> index.indexPending("s", null));
    assertEquals(1, repo.pendingDense("s", 50).size());
    repo.saveBatch("s", 1, 2, List.of(EpisodeRevisionPagingTest.episode("v2")));
    reset(vectors);
    var restarted = new EpisodeDenseIndex(new EpisodeRepository(ds), vectors);
    restarted.indexPending("s", null);
    restarted.indexPending("s", null);
    verify(vectors, times(1))
        .replaceBySource(
            eq("p"),
            eq("e"),
            anyString(),
            argThat(d -> d.getFirst().getMetadata().get("revision").equals("v2")));
    assertTrue(repo.pendingDense("s", 50).isEmpty());
    assertEquals(2, repo.checkpoint("s"));
  }

  /** ベクトルによる検索について、古い改訂一覧を受け付けないことを確認する。 */
  @Test
  void denseSearchRejectsStaleRevisionsAndForeignProjectsBeforeReturningStoredText() {
    var repo =
        new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("m.db")));
    repo.saveBatch("s", 0, 1, List.of(EpisodeRevisionPagingTest.episode("v2")));
    repo.markDense(EpisodeRevisionPagingTest.episode("v2"));
    var vectors = mock(LazySqliteVectorStore.class);
    var index = new EpisodeDenseIndex(repo, vectors);
    when(vectors.denseSearch(any(SearchRequest.class)))
        .thenReturn(
            List.of(
                Document.builder()
                    .id("chunk")
                    .text("untrusted stale text")
                    .metadata(Map.of("episodeId", "e", "revision", "v1"))
                    .build()));
    assertTrue(index.search("synonym", "p", 8, EpisodeTimeRange.of(null, null)).isEmpty());
    when(vectors.denseSearch(any(SearchRequest.class)))
        .thenReturn(
            List.of(
                Document.builder()
                    .id("chunk")
                    .text("do not use vector text")
                    .metadata(Map.of("episodeId", "e", "revision", "v2"))
                    .build()));
    assertEquals(
        "summary",
        index.search("synonym", "p", 8, EpisodeTimeRange.of(null, null)).getFirst().summary());
    assertTrue(index.search("synonym", "foreign", 8, EpisodeTimeRange.of(null, null)).isEmpty());
    assertTrue(index.search("synonym", "p", 8, EpisodeTimeRange.of("2027-01-01", null)).isEmpty());
  }
}
