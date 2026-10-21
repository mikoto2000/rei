package dev.mikoto2000.rei.episode;

import dev.mikoto2000.rei.llm.ModelCallBudget;
import dev.mikoto2000.rei.llm.ModelCallBudgetScope;
import dev.mikoto2000.rei.vectorstore.LazySqliteVectorStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * 過去の経験の活用に必要な経験の記録ベクトルによる位置を扱う。
 *
 * <p>Separate derived store: vector text is never trusted as an authoritative episode.
 */
public class EpisodeDenseIndex {
  /** 保存と検索の窓口。 */
  private final EpisodeRepository repository;

  /** ベクトル一覧。 */
  private final LazySqliteVectorStore vectors;

  /** 生成。 */
  private final String generation;

  /** 取得元利用可能。 */
  private java.util.function.Predicate<Episode> sourceAvailable = e -> true;

  /** 過去の経験の活用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public EpisodeDenseIndex(EpisodeRepository repository, LazySqliteVectorStore vectors) {
    this(repository, vectors, "default");
  }

  /** 指定された値や利用先を使って、EpisodeDenseIndexを初期化する。 */
  public EpisodeDenseIndex(
      EpisodeRepository repository, LazySqliteVectorStore vectors, String generation) {
    this.repository = repository;
    this.vectors = vectors;
    this.generation = generation;
  }

  /** 過去の経験の活用で使用する取得元利用可能を設定する。 */
  public void setSourceAvailable(java.util.function.Predicate<Episode> check) {
    sourceAvailable = check;
  }

  /** 後続の処理で判断できるよう、未完了の対象を返す。 */
  public int pending(String session) {
    return repository.pendingDense(session, 1, generation).size();
  }

  /** 過去の経験の活用に関する位置待機中を処理する。 */
  public void indexPending(String session, ModelCallBudget budget) {
    var pending = repository.pendingDense(session, 50, generation);
    if (pending.isEmpty()) return;
    String worker = UUID.randomUUID().toString();
    if (!repository.acquire("__episode_dense_index", worker, 600)) return;
    try (var scope = ModelCallBudgetScope.open(budget)) {
      for (var e : repository.pendingDense(session, 50, generation)) {
        if (Thread.currentThread().isInterrupted())
          throw new java.util.concurrent.CancellationException();
        if (!sourceAvailable.test(e)) continue;
        String content =
            e.title()
                + " "
                + e.summary()
                + " "
                + e.claims().stream()
                    .map(c -> c.kind() + " " + c.evidence() + " " + c.text())
                    .collect(java.util.stream.Collectors.joining(" "));
        var document =
            Document.builder()
                .id(e.id())
                .text(content.substring(0, Math.min(4000, content.length())))
                .metadata(
                    Map.of(
                        "docId",
                        e.projectId(),
                        "source",
                        e.id(),
                        "chunkIndex",
                        0,
                        "ingestedAt",
                        e.occurredAt(),
                        "episodeId",
                        e.id(),
                        "revision",
                        e.revision()))
                .build();
        vectors.replaceBySource(e.projectId(), e.id(), e.occurredAt(), List.of(document));
        if (!repository.markDense(e, generation)) repository.invalidateDense(e.id());
        else if (budget instanceof dev.mikoto2000.rei.memory.service.AutoSleepExecution auto)
          auto.progressSaved();
      }
    } finally {
      repository.release("__episode_dense_index", worker);
    }
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public List<Episode> search(String query, String project, int limit, EpisodeTimeRange range) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (limit < 1 || limit > 20 || query == null || query.length() > 2000)
      throw new IllegalArgumentException("Invalid dense query");
    if (!repository.hasDense(project, generation)) return List.of();
    var request =
        SearchRequest.builder()
            .query(query)
            .topK(limit)
            .similarityThreshold(0)
            .filterExpression(new FilterExpressionBuilder().eq("docId", project).build())
            .build();
    var result = new LinkedHashMap<String, Episode>();
    for (var document : vectors.denseSearch(request)) {
      var id = document.getMetadata().get("episodeId");
      var revision = document.getMetadata().get("revision");
      if (id instanceof String key)
        repository
            .find(key, project)
            .filter(
                e ->
                    e.revision().equals(revision)
                        && range.includes(java.time.Instant.parse(e.occurredAt())))
            .ifPresent(e -> result.putIfAbsent(e.id(), e));
    }
    return List.copyOf(result.values());
  }
}
