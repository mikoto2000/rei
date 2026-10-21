package dev.mikoto2000.rei.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.skills.AgentSkill;
import dev.mikoto2000.rei.skills.SemanticSkillProperties;
import dev.mikoto2000.rei.skills.SkillMetadataEmbedding;
import dev.mikoto2000.rei.vectordocument.CandidateReranker;
import dev.mikoto2000.rei.vectorstore.HybridRetrievalProperties;
import dev.mikoto2000.rei.vectorstore.RetrievalCandidates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/** 関連情報の取得品質処理の連携の結果と状態の契約を検証するテスト。 */
class RetrievalQualityPipelineTest {
  /**
   * 検証条件の再現に使う項目の情報。
   *
   * @param id 検証に使う識別子
   * @param text 検証に使うテキスト
   */
  record Item(String id, String text) {}

  /**
   * 検証条件の再現に使う例の情報。
   *
   * @param domain 検証に使うドメイン
   * @param fixture 検証に使う検証条件
   * @param corpus 検証に使うcorpus
   */
  record Example(String domain, RetrievalQualityEvaluation.Case fixture, List<Item> corpus) {}

  /**
   * 検証条件の再現に使う例一覧の情報。
   *
   * @param examples 検証に使う例一覧
   */
  record Examples(List<Example> examples) {}

  /** {@code fixtures} の条件を再現するための補助情報を返す。 */
  List<Example> fixtures() throws Exception {
    try (var input = getClass().getResourceAsStream("/evaluation/retrieval-quality.json")) {
      assertNotNull(input);
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .readValue(input, Examples.class)
          .examples();
    }
  }

  /** {@code ranker} の条件を再現するための補助情報を返す。 */
  CandidateReranker ranker() {
    return new CandidateReranker() {
      /** {@code rerank} の条件を再現するための補助情報を返す。 */
      public <T> List<T> rerank(
          String query, List<T> candidates, java.util.function.Function<T, String> text) {
        assertTrue(
            candidates.stream().map(text).noneMatch(s -> s.contains("PRIVATE_INSTRUCTIONS")));
        var result = new ArrayList<>(candidates);
        result.sort(
            Comparator.comparingInt(
                c ->
                    text.apply(c).contains("Technical narrative")
                            || text.apply(c).contains("Durable receipts")
                        ? 0
                        : 1));
        return result;
      }
    };
  }

  /** {@code skills} の条件を再現するための補助情報を返す。 */
  SkillRetrievalEvaluationAdapter skills(
      Example fixture, SkillMetadataEmbedding embedding, CandidateReranker reranker) {
    var catalog =
        fixture.corpus().stream()
            .map(
                item ->
                    new AgentSkill(
                        item.id(),
                        item.text(),
                        List.of(),
                        true,
                        Path.of(item.id()),
                        Path.of(item.id(), "SKILL.md"),
                        "PRIVATE_INSTRUCTIONS"))
            .toList();
    return new SkillRetrievalEvaluationAdapter(
        catalog, new SemanticSkillProperties(true, 64, .55, 30), embedding, reranker);
  }

  /** 成果の評価で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void actualHybridFusionAndRerankAreComparedAgainstLabelledHardNegative() throws Exception {
    var example =
        fixtures().stream().filter(f -> f.domain().equals("document")).findFirst().orElseThrow();
    var corpus = new HashMap<String, Document>();
    example
        .corpus()
        .forEach(
            item ->
                corpus.put(item.id(), Document.builder().id(item.id()).text(item.text()).build()));
    var backend =
        new RetrievalCandidates() {
          /** {@code lexicalSearch} の条件を再現するための補助情報を返す。 */
          public List<Document> lexicalSearch(SearchRequest request) {
            assertEquals(example.fixture().query(), request.getQuery());
            return List.of(corpus.get("artifact-ui"), corpus.get("artifact-recovery"));
          }

          /** {@code denseSearch} の条件を再現するための補助情報を返す。 */
          public List<Document> denseSearch(SearchRequest request) {
            return List.of(corpus.get("artifact-recovery"), corpus.get("neutral"));
          }
        };
    var result =
        new RetrievalQualityEvaluation()
            .compare(
                List.of(example.fixture()),
                "fixture-vector-provider",
                new HybridRetrievalEvaluationAdapter(
                    backend, new HybridRetrievalProperties(true, 10, 60), ranker()));
    assertEquals(0, result.aggregate().get("LEXICAL_BASE").mrr());
    assertEquals(1, result.aggregate().get("DENSE_BASE").mrr());
    assertEquals(1, result.aggregate().get("RRF_BASE").mrr());
    assertEquals(1, result.aggregate().get("LEXICAL_RERANK").mrr());
    assertEquals(6, result.runs().size());
    Files.createDirectories(Path.of("target/evaluation"));
    new com.fasterxml.jackson.databind.ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(Path.of("target/evaluation/rag-quality.json").toFile(), result);
  }

  /** 付随情報を利用することを確認する。 */
  @Test
  void actualSkillKeywordDenseFusionAndRerankUseMetadataOnly() throws Exception {
    var example =
        fixtures().stream().filter(f -> f.domain().equals("skill")).findFirst().orElseThrow();
    SkillMetadataEmbedding embedding =
        input ->
            input.stream()
                .map(
                    text ->
                        !text.startsWith("name: ") || text.contains("technical narrative")
                            ? new float[] {1, 0}
                            : new float[] {0, 1})
                .toList();
    var result =
        new RetrievalQualityEvaluation()
            .compare(
                List.of(example.fixture()),
                "fixture-skill-embedding",
                skills(example, embedding, ranker()));
    assertEquals(1, result.aggregate().get("DENSE_BASE").recall());
    assertEquals(1, result.aggregate().get("RRF_RERANK").recall());
    assertTrue(
        result.runs().stream()
            .filter(r -> r.mode() == RetrievalQualityEvaluation.Mode.LEXICAL && !r.reranked())
            .noneMatch(r -> r.metrics().expectedTopKMatched()));
    Files.createDirectories(Path.of("target/evaluation"));
    new com.fasterxml.jackson.databind.ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(Path.of("target/evaluation/skill-quality.json").toFile(), result);
  }

  /** 成果の評価で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unavailableProvidersForeignRerankAndFallbackCannotMasqueradeAsEvaluationSuccess()
      throws Exception {
    var example =
        fixtures().stream().filter(f -> f.domain().equals("skill")).findFirst().orElseThrow();
    assertThrows(
        IllegalStateException.class,
        () ->
            skills(example, null, ranker())
                .retrieve(example.fixture(), RetrievalQualityEvaluation.Mode.DENSE, false));
    assertThrows(
        IllegalStateException.class,
        () ->
            skills(
                    example,
                    input -> {
                      throw new IllegalStateException("fixture failure");
                    },
                    ranker())
                .retrieve(example.fixture(), RetrievalQualityEvaluation.Mode.DENSE, false));
    var failedRanker =
        new CandidateReranker() {
          /** {@code rerank} の条件を再現するための補助情報を返す。 */
          public <T> List<T> rerank(
              String q, List<T> candidates, java.util.function.Function<T, String> text) {
            throw new IllegalStateException("ranker unavailable");
          }
        };
    assertThrows(
        IllegalStateException.class,
        () ->
            skills(
                    example,
                    input -> input.stream().map(text -> new float[] {1, 0}).toList(),
                    failedRanker)
                .retrieve(example.fixture(), RetrievalQualityEvaluation.Mode.RRF, true));
  }
}
