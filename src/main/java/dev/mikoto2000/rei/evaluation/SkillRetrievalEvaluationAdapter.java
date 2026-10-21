package dev.mikoto2000.rei.evaluation;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.skills.AgentSkill;
import dev.mikoto2000.rei.skills.SemanticSkillProperties;
import dev.mikoto2000.rei.skills.SemanticSkillSearch;
import dev.mikoto2000.rei.skills.SkillCandidate;
import dev.mikoto2000.rei.skills.SkillCandidateSelector;
import dev.mikoto2000.rei.skills.SkillMetadataEmbedding;
import dev.mikoto2000.rei.vectordocument.CandidateReranker;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * スキル関連情報の取得評価を別の利用先へ接続する。
 *
 * <p>Uses live-catalog keyword search and existing SemanticSkillSearch with controlled rank
 * streams.
 */
public final class SkillRetrievalEvaluationAdapter implements RetrievalQualityEvaluation.Retriever {
  /** スキル一覧。 */
  private final List<AgentSkill> skills;

  /** 設定。 */
  private final SemanticSkillProperties settings;

  /** 埋め込み表現。 */
  private final SkillMetadataEmbedding embedding;

  /** 候補の関連度を再評価する窓口。 */
  private final CandidateReranker reranker;

  /** 指定された値や利用先を使って、SkillRetrievalEvaluationAdapterを初期化する。 */
  public SkillRetrievalEvaluationAdapter(
      List<AgentSkill> skills,
      SemanticSkillProperties settings,
      SkillMetadataEmbedding embedding,
      CandidateReranker reranker) {
    this.skills = List.copyOf(skills);
    this.settings = Objects.requireNonNull(settings);
    this.embedding = embedding;
    this.reranker = reranker;
    if (skills.stream().map(AgentSkill::name).distinct().count() != skills.size())
      throw new IllegalArgumentException("Unique fixture skill names required");
  }

  /** 成果の評価で用いる取得を返す。 */
  public List<String> retrieve(
      RetrievalQualityEvaluation.Case fixture,
      RetrievalQualityEvaluation.Mode mode,
      boolean rerank) {
    RunCancellation.propagate(null);
    int pool = Math.min(256, Math.max(10, fixture.topK()));
    var lexical = new SkillCandidateSelector().selectCandidates(fixture.query(), skills, pool);
    List<SkillCandidate> result;
    if (mode == RetrievalQualityEvaluation.Mode.LEXICAL) {
      result = lexical;
      if (rerank) {
        if (reranker == null)
          throw new IllegalStateException("Rerank evaluation provider unavailable");
        var ranked =
            reranker.rerankForEvaluation(
                fixture.query(), result, c -> c.skill().name() + "\n" + c.skill().description());
        RunCancellation.propagate(null);
        if (ranked == null
            || ranked.size() != result.size()
            || !new HashSet<>(result.stream().map(c -> c.skill().name()).toList())
                .equals(new HashSet<>(ranked.stream().map(c -> c.skill().name()).toList())))
          throw new IllegalStateException("Reranker changed skill candidates");
        result = ranked;
      }
    } else {
      if (!settings.enabled() || embedding == null || rerank && reranker == null)
        throw new IllegalStateException("Explicit evaluation embedding/rerank provider required");
      // A single dense rank stream preserves dense ordering through existing RRF; no keyword
      // fallback can disguise an unavailable model.
      var failure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
      var dimensions = new java.util.concurrent.atomic.AtomicInteger();
      SkillMetadataEmbedding strict =
          input -> {
            try {
              var vectors = embedding.embed(input);
              if (vectors == null
                  || vectors.size() != input.size()
                  || vectors.stream()
                      .anyMatch(vector -> vector == null || vector.length == 0 || !finite(vector)))
                throw new IllegalStateException("Invalid evaluation embeddings");
              for (var vector : vectors) {
                dimensions.compareAndSet(0, vector.length);
                if (vector.length != dimensions.get())
                  throw new IllegalStateException("Evaluation embedding dimensions changed");
              }
              return vectors;
            } catch (RuntimeException error) {
              RunCancellation.propagate(error);
              failure.set(error);
              throw error;
            }
          };
      result =
          new SemanticSkillSearch(
                  settings,
                  () -> strict,
                  () -> rerank ? new StrictReranker(reranker, failure) : null,
                  System::nanoTime)
              .select(
                  fixture.query(),
                  skills,
                  mode == RetrievalQualityEvaluation.Mode.DENSE ? List.of() : lexical,
                  pool);
      if (failure.get() != null
          || result.stream().anyMatch(c -> !c.matchedFields().contains("rrf")))
        throw new IllegalStateException(
            "Semantic evaluation fell back; no dense/RRF quality result", failure.get());
    }
    return result.stream().limit(fixture.topK()).map(c -> c.skill().name()).toList();
  }

  /** 成果の評価において、有限に該当するか判定する。 */
  private static boolean finite(float[] vector) {
    for (float value : vector) if (!Float.isFinite(value)) return false;
    return true;
  }

  /** 成果の評価に必要な厳格なRerankerを扱う。 */
  private static final class StrictReranker implements CandidateReranker {
    /** 委譲先。 */
    private final CandidateReranker delegate;

    /** 失敗情報。 */
    private final java.util.concurrent.atomic.AtomicReference<RuntimeException> failure;

    /** 指定された値や利用先を使って、StrictRerankerを初期化する。 */
    StrictReranker(
        CandidateReranker delegate,
        java.util.concurrent.atomic.AtomicReference<RuntimeException> failure) {
      this.delegate = delegate;
      this.failure = failure;
    }

    /** 成果の評価で用いる再評価を返す。 */
    public <T> List<T> rerank(
        String query, List<T> candidates, java.util.function.Function<T, String> text) {
      try {
        var result = delegate.rerankForEvaluation(query, candidates, text);
        if (result == null
            || result.size() != candidates.size()
            || new HashSet<>(result).size() != result.size()
            || !new HashSet<>(result).equals(new HashSet<>(candidates)))
          throw new IllegalStateException("Evaluation reranker changed candidates");
        return result;
      } catch (RuntimeException error) {
        RunCancellation.propagate(error);
        failure.set(error);
        throw error;
      }
    }
  }
}
