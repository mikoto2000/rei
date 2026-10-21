package dev.mikoto2000.rei.vectordocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import dev.mikoto2000.rei.vectorstore.HybridRetrievalProperties;
import dev.mikoto2000.rei.vectorstore.RetrievalCandidates;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

/** Hybrid文書関連情報の取得の結果と状態の契約を検証するテスト。 */
class HybridDocumentRetrievalTest {
  /** 検索対象文書の管理で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void bm25OptInFlowsThroughDocumentGroupingAndReranking() {
    var store = mock(VectorStore.class, withSettings().extraInterfaces(RetrievalCandidates.class));
    var backend = (RetrievalCandidates) store;
    when(backend.bm25Search(any())).thenReturn(List.of(doc("bm25")));
    when(backend.denseSearch(any())).thenReturn(List.of(doc("dense")));
    var service =
        new VectorDocumentService(
            store, mock(VectorDocumentRepository.class), new VectorDocumentProperties(512, 0));
    service.setRetrieval(new HybridRetrievalProperties(true, 40, 60, true));
    service.setRerankService(
        new CandidateReranker() {
          /** {@code rerank} の条件を再現するための補助情報を返す。 */
          public <T> List<T> rerank(
              String q, List<T> candidates, java.util.function.Function<T, String> text) {
            assertEquals(2, candidates.size());
            return candidates;
          }
        });
    assertEquals(2, service.search("spring", 2, null, null).size());
    verify(backend).bm25Search(any());
    verify(backend, never()).lexicalSearch(any());
  }

  /** {@code doc} の条件を再現するための補助情報を返す。 */
  Document doc(String id) {
    return Document.builder()
        .id(id + "#0")
        .text("body " + id)
        .metadata(Map.of("docId", id, "source", id + ".txt", "chunkIndex", 0))
        .score(.9)
        .build();
  }

  /** 両方逐次データ一覧を利用することを確認する。 */
  @Test
  void optInUsesBothStreamsAndExistingRerankerBeforeFinalLimit() {
    var store = mock(VectorStore.class, withSettings().extraInterfaces(RetrievalCandidates.class));
    var backend = (RetrievalCandidates) store;
    when(backend.denseSearch(any())).thenReturn(List.of(doc("a"), doc("b")));
    when(backend.lexicalSearch(any())).thenReturn(List.of(doc("a")));
    var service =
        new VectorDocumentService(
            store, mock(VectorDocumentRepository.class), new VectorDocumentProperties(512, 0));
    service.setRetrieval(new HybridRetrievalProperties(true, 40, 60));
    var seen = new java.util.concurrent.atomic.AtomicInteger();
    service.setRerankService(
        new CandidateReranker() {
          /** {@code rerank} の条件を再現するための補助情報を返す。 */
          public <T> List<T> rerank(
              String q, List<T> candidates, java.util.function.Function<T, String> text) {
            seen.set(candidates.size());
            assertEquals("body a", text.apply(candidates.getFirst()));
            return candidates.reversed();
          }
        });
    var results = service.search("question", 1, null, null);
    assertEquals(2, seen.get());
    assertEquals("b", results.getFirst().docId());
    assertEquals(1, results.size());
    verify(store, never())
        .similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class));
    assertThrows(IllegalArgumentException.class, () -> service.search("question", 65, null, null));
  }

  /** 検索対象文書の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void defaultRemainsLegacyAndUnsupportedOptInBackendFailsClearly() {
    var store = mock(VectorStore.class);
    when(store.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
        .thenReturn(List.of(doc("legacy")));
    var service =
        new VectorDocumentService(
            store, mock(VectorDocumentRepository.class), new VectorDocumentProperties(512, 0));
    assertEquals("legacy", service.search("question", 1, null, null).getFirst().docId());
    service.setRetrieval(new HybridRetrievalProperties(true, 40, 60));
    assertThrows(IllegalStateException.class, () -> service.search("question", 1, null, null));
  }
}
