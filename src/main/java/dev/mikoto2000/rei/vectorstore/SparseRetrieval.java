package dev.mikoto2000.rei.vectorstore;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/**
 * 関連文書の検索に必要なSparse関連情報の取得を扱う。
 *
 * <p>Explicitly constructed optional encoder/index adapter. Absence leaves existing BM25 retrieval
 * intact.
 */
public final class SparseRetrieval {
  /** 関連文書の検索に必要な位置を扱う。 */
  public interface Index {
    /** 関連文書の検索で用いるモデルの識別子を返す。 */
    String modelId();

    /** 関連文書の検索で用いる次元数を返す。 */
    int dimensions();

    /** 関連文書の検索において、絞り込み条件一覧への対応可否を返す。 */
    default boolean supportsFilters() {
      return false;
    }

    /**
     * 指定された検索条件に合う情報を探し、候補を返す。
     *
     * <p>Implementations must apply the request filter and threshold before topK.
     */
    List<Document> search(SearchRequest request, SparseEncoder.Vector vector);

    /** 指定された検索条件に合う情報を探し、候補を返す。 */
    default List<Document> search(
        SearchRequest request, SparseEncoder.Vector vector, Runnable active) {
      active.run();
      var result = search(request, vector);
      active.run();
      return result;
    }
  }

  /**
   * 関連文書の検索で受け渡す結果を表す。
   *
   * @param mode 動作モード
   * @param reason 理由
   * @param modelId モデルの識別子
   * @param documents 保存済み文書
   * @param truthVerified 確定情報確認済み
   */
  public record Result(
      String mode,
      String reason,
      String modelId,
      List<Document> documents,
      boolean truthVerified) {}

  /** 比較の基準。 */
  private final RetrievalCandidates baseline;

  /** 送信形式への変換。 */
  private final SparseEncoder encoder;

  /** 位置。 */
  private final Index index;

  /** ナノ秒数。 */
  private final LongSupplier nanos;

  /** タイムアウト。 */
  private final long timeout;

  /** 関連文書の検索を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SparseRetrieval(RetrievalCandidates baseline, SparseEncoder encoder, Index index) {
    this(baseline, encoder, index, System::nanoTime, Duration.ofSeconds(5));
  }

  /** 指定された値や利用先を使って、SparseRetrievalを初期化する。 */
  public SparseRetrieval(
      RetrievalCandidates baseline,
      SparseEncoder encoder,
      Index index,
      LongSupplier nanos,
      Duration timeout) {
    this.baseline = Objects.requireNonNull(baseline);
    this.encoder = encoder;
    this.index = index;
    this.nanos = Objects.requireNonNull(nanos);
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException("Sparse deadline <=30s");
    this.timeout = timeout.toNanos();
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public Result search(SearchRequest request) {
    RunCancellation.propagate(null);
    if (request == null
        || request.getQuery() == null
        || request.getQuery().length() > 8192
        || request.getTopK() < 1
        || request.getTopK() > 256)
      throw new IllegalArgumentException("Bounded sparse query/topK required");
    if (encoder == null || index == null) return fallback(request, "SPARSE_NOT_CONFIGURED");
    if (request.hasFilterExpression() && !index.supportsFilters())
      return fallback(request, "SPARSE_FILTER_UNSUPPORTED");
    long started = nanos.getAsLong();
    Runnable active =
        () -> {
          RunCancellation.propagate(null);
          if (nanos.getAsLong() - started >= timeout) throw new Deadline();
        };
    try {
      active.run();
      if (!Objects.equals(encoder.modelId(), index.modelId())
          || index.dimensions() < 1
          || index.dimensions() > 1048576) throw new Fallback("SPARSE_MODEL_MISMATCH");
      var vector = encoder.encode(request.getQuery(), active);
      active.run();
      if (vector == null
          || !Objects.equals(vector.modelId(), encoder.modelId())
          || vector.dimensions() != index.dimensions()) throw new Fallback("SPARSE_MODEL_MISMATCH");
      if (vector.weights().isEmpty()) throw new Fallback("SPARSE_EMPTY_VECTOR");
      var documents = index.search(request, vector, active);
      active.run();
      return new Result(
          "LEARNED_SPARSE",
          "SPARSE_PROVIDER_USED",
          vector.modelId(),
          bounded(documents, request),
          false);
    } catch (RuntimeException failure) {
      RunCancellation.propagate(failure);
      if (failure instanceof dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException)
        throw failure;
      return fallback(
          request,
          failure instanceof Fallback selected
              ? selected.reason
              : failure instanceof Deadline ? "SPARSE_TIMEOUT" : "SPARSE_UNAVAILABLE");
    }
  }

  /** 関連文書の検索で用いる代替手段を返す。 */
  private Result fallback(SearchRequest request, String reason) {
    RunCancellation.propagate(null);
    return new Result("BM25", reason, null, bounded(baseline.bm25Search(request), request), false);
  }

  /** 利用先の上限を超えない量へ、指定された内容を調整する。 */
  private static List<Document> bounded(List<Document> documents, SearchRequest request) {
    if (documents == null
        || documents.size() > request.getTopK()
        || documents.stream()
            .anyMatch(doc -> doc == null || doc.getId() == null || doc.getId().isBlank())
        || documents.stream().map(Document::getId).distinct().count() != documents.size())
      throw new IllegalStateException("Invalid bounded sparse/BM25 candidates");
    return List.copyOf(documents);
  }

  /** 関連文書の検索に必要な代替手段を扱う。 */
  private static final class Fallback extends RuntimeException {
    /** 理由。 */
    private final String reason;

    /** 指定された値や利用先を使って、Fallbackを初期化する。 */
    Fallback(String reason) {
      this.reason = reason;
    }
  }

  /** 関連文書の検索に必要な期限を扱う。 */
  private static final class Deadline extends RuntimeException {}
}
