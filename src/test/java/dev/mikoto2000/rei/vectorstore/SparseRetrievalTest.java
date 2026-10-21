package dev.mikoto2000.rei.vectorstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/** Sparse関連情報の取得の結果と状態の契約を検証するテスト。 */
class SparseRetrievalTest {
  /** 関連文書の検索で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedBaselineIsNotRetriedAsProviderFailure() {
    var backend = mock(RetrievalCandidates.class);
    when(backend.bm25Search(any())).thenThrow(new IllegalStateException("baseline unavailable"));
    var mismatched =
        new SparseRetrieval.Index() {
          /** {@code modelId} の条件を再現するための補助情報を返す。 */
          public String modelId() {
            return "other";
          }

          /** {@code dimensions} の条件を再現するための補助情報を返す。 */
          public int dimensions() {
            return 100;
          }

          /** 検証用に、指定された検索条件に合う情報を探し、候補を返す。 */
          public List<Document> search(SearchRequest request, SparseEncoder.Vector vector) {
            throw new AssertionError();
          }
        };
    assertThrows(
        IllegalStateException.class,
        () -> new SparseRetrieval(backend, encoder(), mismatched).search(request()));
    verify(backend, times(1)).bm25Search(any());
  }

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  SearchRequest request() {
    return SearchRequest.builder().query("artifact ownership").topK(2).build();
  }

  /** {@code encoder} の条件を再現するための補助情報を返す。 */
  SparseEncoder encoder() {
    return new SparseEncoder() {
      /** {@code modelId} の条件を再現するための補助情報を返す。 */
      public String modelId() {
        return "fixture-v1";
      }

      /** 検証用に、受け渡し先が扱える形式へ、指定された内容を変換する。 */
      public SparseEncoder.Vector encode(String text, Runnable active) {
        active.run();
        return new SparseEncoder.Vector("fixture-v1", 100, Map.of(2, 1.0, 8, .25));
      }
    };
  }

  /** {@code index} の条件を再現するための補助情報を返す。 */
  SparseRetrieval.Index index() {
    return new SparseRetrieval.Index() {
      /** {@code modelId} の条件を再現するための補助情報を返す。 */
      public String modelId() {
        return "fixture-v1";
      }

      /** {@code dimensions} の条件を再現するための補助情報を返す。 */
      public int dimensions() {
        return 100;
      }

      /** 検証用に、指定された検索条件に合う情報を探し、候補を返す。 */
      public List<Document> search(SearchRequest request, SparseEncoder.Vector vector) {
        assertEquals(2, request.getTopK());
        assertEquals(.25, vector.weights().get(8));
        return List.of(Document.builder().id("sparse").text("bounded result").build());
      }
    };
  }

  /** 関連文書の検索で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void absentProviderUsesExistingBm25WithoutDenseOrEncoderCalls() {
    var backend = mock(RetrievalCandidates.class);
    when(backend.bm25Search(any()))
        .thenReturn(List.of(Document.builder().id("baseline").text("BM25").build()));
    var result = new SparseRetrieval(backend, null, null).search(request());
    assertEquals("BM25", result.mode());
    assertEquals("SPARSE_NOT_CONFIGURED", result.reason());
    assertEquals("baseline", result.documents().getFirst().getId());
    verify(backend).bm25Search(any());
    verifyNoMoreInteractions(backend);
  }

  /** 関連文書の検索で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void optionalProviderReturnsBoundedSparseEvidenceWithoutChangingFallback() {
    var backend = mock(RetrievalCandidates.class);
    var result = new SparseRetrieval(backend, encoder(), index()).search(request());
    assertEquals("LEARNED_SPARSE", result.mode());
    assertEquals("fixture-v1", result.modelId());
    assertEquals("sparse", result.documents().getFirst().getId());
    assertFalse(result.truthVerified());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(backend);
  }

  /** 関連文書の検索で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidVectorsModelMismatchAndUnavailableProviderFallBack() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SparseEncoder.Vector("fixture", 10, Map.of(10, 1.0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SparseEncoder.Vector("fixture", 10, Map.of(1, Double.NaN)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SparseEncoder.Vector("fixture", 10, Map.of(1, -1.0)));
    var backend = mock(RetrievalCandidates.class);
    when(backend.bm25Search(any())).thenReturn(List.of());
    var unavailable =
        new SparseEncoder() {
          /** {@code modelId} の条件を再現するための補助情報を返す。 */
          public String modelId() {
            return "wrong-model";
          }

          /** 検証用に、受け渡し先が扱える形式へ、指定された内容を変換する。 */
          public SparseEncoder.Vector encode(String text, Runnable check) {
            throw new IllegalStateException("private provider detail");
          }
        };
    var result = new SparseRetrieval(backend, unavailable, index()).search(request());
    assertEquals("BM25", result.mode());
    assertFalse(result.reason().contains("private"));
  }

  /** 関連文書の検索で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void filtersCannotBeBypassedByUnsupportedIndex() {
    var backend = mock(RetrievalCandidates.class);
    when(backend.bm25Search(any())).thenReturn(List.of());
    var filtered =
        SearchRequest.builder()
            .query("artifact")
            .topK(2)
            .filterExpression(
                new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder()
                    .eq("project", "allowed")
                    .build())
            .build();
    var result = new SparseRetrieval(backend, encoder(), index()).search(filtered);
    assertEquals("SPARSE_FILTER_UNSUPPORTED", result.reason());
    verify(backend).bm25Search(filtered);
  }

  /** 関連文書の検索の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAndBudgetExhaustionNeverBecomeBm25Fallback() {
    var backend = mock(RetrievalCandidates.class);
    for (RuntimeException failure :
        List.of(
            new java.util.concurrent.CancellationException(),
            new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
                dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason
                    .LLM_CALL_BUDGET_EXCEEDED))) {
      var encoder =
          new SparseEncoder() {
            /** {@code modelId} の条件を再現するための補助情報を返す。 */
            public String modelId() {
              return "fixture-v1";
            }

            /** 検証用に、受け渡し先が扱える形式へ、指定された内容を変換する。 */
            public SparseEncoder.Vector encode(String text, Runnable check) {
              throw failure;
            }
          };
      assertThrows(
          failure.getClass(),
          () -> new SparseRetrieval(backend, encoder, index()).search(request()));
    }
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(backend);
  }

  /** 関連文書の検索で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void deadlineAndInvalidIndexResultsHaveExplicitBm25Fallback() {
    var backend = mock(RetrievalCandidates.class);
    when(backend.bm25Search(any())).thenReturn(List.of());
    var nanos = new java.util.concurrent.atomic.AtomicLong();
    var slow =
        new SparseEncoder() {
          /** {@code modelId} の条件を再現するための補助情報を返す。 */
          public String modelId() {
            return "fixture-v1";
          }

          /** 検証用に、受け渡し先が扱える形式へ、指定された内容を変換する。 */
          public SparseEncoder.Vector encode(String text, Runnable active) {
            nanos.set(1000001);
            active.run();
            return null;
          }
        };
    assertEquals(
        "SPARSE_TIMEOUT",
        new SparseRetrieval(backend, slow, index(), nanos::get, java.time.Duration.ofMillis(1))
            .search(request())
            .reason());
  }
}
