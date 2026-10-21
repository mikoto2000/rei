package dev.mikoto2000.rei.vectorstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/** Hybrid関連情報の取得の結果と状態の契約を検証するテスト。 */
class HybridRetrievalTest {
  /** 語句による順位逐次データを利用することを確認する。 */
  @Test
  void optInBm25UsesTheLexicalRankStreamAndPreservesItsEvidence() {
    var backend = mock(RetrievalCandidates.class);
    var lexical =
        Document.builder()
            .id("both")
            .text("spring")
            .metadata(Map.of("lexicalMode", "bm25", "bm25Score", 0.25))
            .score(.25)
            .build();
    when(backend.bm25Search(any())).thenReturn(List.of(lexical));
    when(backend.denseSearch(any())).thenReturn(List.of(doc("dense", .99)));
    var results =
        new HybridRetriever(backend, new HybridRetrievalProperties(true, 40, 60, true))
            .search(SearchRequest.builder().query("spring").topK(2).build());
    var result = results.stream().filter(d -> d.getId().equals("both")).findFirst().orElseThrow();
    assertEquals("bm25", result.getMetadata().get("lexicalMode"));
    assertEquals(1, result.getMetadata().get("lexicalRank"));
    assertEquals(.25, result.getMetadata().get("bm25Score"));
    verify(backend, never()).lexicalSearch(any());
  }

  /** {@code doc} の条件を再現するための補助情報を返す。 */
  Document doc(String id, double score) {
    return Document.builder()
        .id(id)
        .text(id)
        .metadata(Map.of("docId", id, "source", "safe", "chunkIndex", 0))
        .score(score)
        .build();
  }

  /** 候補一覧を維持することを確認する。 */
  @Test
  void rankFusionKeepsCandidatesFromEitherRetrieverAndRewardsAgreement() {
    var fusion = new ReciprocalRankFusion(60);
    var results =
        fusion.fuse(
            List.of(List.of("dense-only", "both"), List.of("lexical-only", "both")), s -> s, 5);
    assertEquals(
        List.of("both", "dense-only", "lexical-only"),
        results.stream().map(ReciprocalRankFusion.Ranked::item).toList());
    assertEquals(61.0 / 62, results.getFirst().score(), .000001);
  }

  /** 関連文書の検索で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicatesDoNotAddWeightAndTiesAreStable() {
    var fusion = new ReciprocalRankFusion(60);
    var results = fusion.fuse(List.of(List.of("z", "z"), List.of("a")), s -> s, 2);
    assertEquals(
        List.of("a", "z"), results.stream().map(ReciprocalRankFusion.Ranked::item).toList());
    assertEquals(.5, results.getFirst().score());
    assertThrows(
        IllegalArgumentException.class,
        () -> fusion.fuse(List.of(Collections.nCopies(257, "z")), s -> s, 2));
  }

  /** 処理の連携について、絞り込み条件を保持することを確認する。 */
  @Test
  void pipelinePreservesFilterAndThresholdAndBoundsBothStreams() {
    var backend = mock(RetrievalCandidates.class);
    when(backend.denseSearch(any())).thenReturn(List.of(doc("dense", .99), doc("both", .9)));
    when(backend.lexicalSearch(any())).thenReturn(List.of(doc("lexical", 1), doc("both", .5)));
    var request =
        SearchRequest.builder()
            .query("question")
            .topK(3)
            .similarityThreshold(.4)
            .filterExpression(new FilterExpressionBuilder().eq("source", "safe").build())
            .build();
    var results =
        new HybridRetriever(backend, new HybridRetrievalProperties(true, 40, 60)).search(request);
    assertEquals("both", results.getFirst().getId());
    assertEquals(2, results.getFirst().getMetadata().get("denseRank"));
    assertEquals(2, results.getFirst().getMetadata().get("lexicalRank"));
    assertEquals("rrf", results.getFirst().getMetadata().get("retrievalMode"));
    var requests = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
    verify(backend).denseSearch(requests.capture());
    verify(backend).lexicalSearch(requests.capture());
    for (var captured : requests.getAllValues()) {
      assertEquals(40, captured.getTopK());
      assertEquals(request.getFilterExpression(), captured.getFilterExpression());
      assertEquals(.4, captured.getSimilarityThreshold());
    }
  }

  /** 関連文書の検索の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void blankQueryMakesNoCallsAndFailuresAndCancellationAreNotHidden() {
    var backend = mock(RetrievalCandidates.class);
    var search = new HybridRetriever(backend, new HybridRetrievalProperties(true, 40, 60));
    assertTrue(search.search(SearchRequest.builder().query(" ").topK(3).build()).isEmpty());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(backend);
    when(backend.lexicalSearch(any())).thenThrow(new UnsupportedOperationException("filter"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> search.search(SearchRequest.builder().query("query").build()));
    verify(backend, never()).denseSearch(any());
    assertThrows(
        IllegalArgumentException.class, () -> new HybridRetrievalProperties(true, 257, 60));
    try {
      Thread.currentThread().interrupt();
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> search.search(SearchRequest.builder().query("query").build()));
    } finally {
      Thread.interrupted();
    }
  }
}
