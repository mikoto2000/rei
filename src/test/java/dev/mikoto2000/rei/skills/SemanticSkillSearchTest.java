package dev.mikoto2000.rei.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 意味によるスキル検索の結果と状態の契約を検証するテスト。 */
class SemanticSkillSearchTest {
  /** {@code skill} の条件を再現するための補助情報を返す。 */
  AgentSkill skill(String name, String description, boolean enabled) {
    return new AgentSkill(
        name,
        description,
        List.of(),
        enabled,
        Path.of(name),
        Path.of(name, "SKILL.md"),
        "private instructions must never be embedded");
  }

  /** 検証で使用する設定を、指定された条件に合わせて用意する。 */
  SemanticSkillProperties config() {
    return new SemanticSkillProperties(true, 64, .55, 30);
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void paraphraseFindsSkillAndDisabledSkillsAndInstructionsAreExcluded() {
    var texts = new ArrayList<String>();
    SkillMetadataEmbedding model =
        input -> {
          texts.addAll(input);
          return input.stream()
              .map(
                  s ->
                      s.contains("writer") || s.contains("compose")
                          ? new float[] {1, 0}
                          : new float[] {0, 1})
              .toList();
        };
    var selector = new SkillCandidateSelector();
    selector.semanticSearch(
        new SemanticSkillSearch(config(), () -> model, () -> null, System::nanoTime));
    var result =
        selector.selectCandidates(
            "compose a narrative",
            List.of(
                skill("writer", "technical articles", true),
                skill("disabled", "compose", false),
                skill("shell", "commands", true)),
            5);
    assertEquals(List.of("writer"), result.stream().map(c -> c.skill().name()).toList());
    assertTrue(result.getFirst().matchedFields().contains("semantic"));
    assertTrue(texts.stream().noneMatch(t -> t.contains("private") || t.contains("disabled")));
  }

  /** 現在スキル対象データを返すことを確認する。 */
  @Test
  void metadataCacheRefreshesAndAlwaysReturnsCurrentSkillObject() {
    var calls = new AtomicInteger();
    SkillMetadataEmbedding model =
        input -> {
          calls.incrementAndGet();
          return input.stream().map(s -> new float[] {1, 0}).toList();
        };
    var selector = new SkillCandidateSelector();
    selector.semanticSearch(
        new SemanticSkillSearch(config(), () -> model, () -> null, System::nanoTime));
    var original = skill("writer", "articles", true);
    selector.selectCandidates("compose", List.of(original), 5);
    assertEquals(2, calls.get());
    var replacement =
        new AgentSkill(
            original.name(),
            original.description(),
            original.keywords(),
            true,
            original.directory(),
            original.skillFile(),
            "updated instructions");
    assertSame(
        replacement,
        selector.selectCandidates("compose", List.of(replacement), 5).getFirst().skill());
    assertEquals(3, calls.get());
    selector.selectCandidates("compose", List.of(skill("writer", "revised description", true)), 5);
    assertEquals(5, calls.get());
  }

  /** スキルの利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void disabledOverLimitAndInvalidVectorsFallBackWithoutRepeatedModelCalls() {
    var calls = new AtomicInteger();
    SkillMetadataEmbedding failed =
        input -> {
          calls.incrementAndGet();
          throw new IllegalStateException("private failure");
        };
    var nanos = new AtomicLong();
    var selector = new SkillCandidateSelector();
    selector.semanticSearch(
        new SemanticSkillSearch(
            new SemanticSkillProperties(false, 64, .55, 30), () -> failed, () -> null, nanos::get));
    assertFalse(
        selector
            .selectCandidates("writer", List.of(skill("writer", "articles", true)), 5)
            .isEmpty());
    assertEquals(0, calls.get());
    selector.semanticSearch(
        new SemanticSkillSearch(config(), () -> failed, () -> null, nanos::get));
    selector.selectCandidates("writer", List.of(skill("writer", "articles", true)), 5);
    selector.selectCandidates("writer", List.of(skill("writer", "articles", true)), 5);
    assertEquals(1, calls.get());
    nanos.set(31_000_000_000L);
    selector.selectCandidates("writer", List.of(skill("writer", "articles", true)), 5);
    assertEquals(2, calls.get());
    selector.semanticSearch(
        new SemanticSkillSearch(
            new SemanticSkillProperties(true, 1, .55, 30), () -> failed, () -> null, nanos::get));
    selector.selectCandidates(
        "writer", List.of(skill("writer", "articles", true), skill("shell", "commands", true)), 5);
    assertEquals(2, calls.get());
  }

  /** スキルの利用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationIsPropagatedRatherThanConvertedToKeywordFallback() {
    SkillMetadataEmbedding model =
        input -> {
          throw new java.util.concurrent.CancellationException();
        };
    var selector = new SkillCandidateSelector();
    selector.semanticSearch(
        new SemanticSkillSearch(config(), () -> model, () -> null, System::nanoTime));
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> selector.selectCandidates("writer", List.of(skill("writer", "articles", true)), 5));
  }

  /** 形式が不正なベクトル一覧について、検索語句候補一覧を保持することを確認する。 */
  @Test
  void malformedVectorsPreserveKeywordCandidates() {
    for (var vector : List.of(new float[] {Float.NaN}, new float[0])) {
      var selector = new SkillCandidateSelector();
      selector.semanticSearch(
          new SemanticSkillSearch(
              config(),
              () -> input -> input.stream().map(s -> vector).toList(),
              () -> null,
              System::nanoTime));
      var result =
          selector.selectCandidates("writer", List.of(skill("writer", "articles", true)), 5);
      assertEquals("writer", result.getFirst().skill().name());
      assertFalse(result.getFirst().matchedFields().contains("rrf"));
    }
  }

  /** スキルの利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void rerankingRunsBeforeLimitAndRejectsForeignIdentities() {
    SkillMetadataEmbedding model = input -> input.stream().map(s -> new float[] {1, 0}).toList();
    var ranker =
        new dev.mikoto2000.rei.vectordocument.CandidateReranker() {
          /** {@code rerank} の条件を再現するための補助情報を返す。 */
          public <T> List<T> rerank(
              String query, List<T> candidates, java.util.function.Function<T, String> text) {
            assertEquals(2, candidates.size());
            assertTrue(
                candidates.stream().map(text).noneMatch(s -> s.contains("private instructions")));
            var reversed = new ArrayList<>(candidates);
            Collections.reverse(reversed);
            return reversed;
          }
        };
    var selector = new SkillCandidateSelector();
    selector.semanticSearch(
        new SemanticSkillSearch(config(), () -> model, () -> ranker, System::nanoTime));
    assertEquals(
        "writer",
        selector
            .selectCandidates(
                "compose",
                List.of(skill("shell", "commands", true), skill("writer", "articles", true)),
                1)
            .getFirst()
            .skill()
            .name());
    var invalid =
        new dev.mikoto2000.rei.vectordocument.CandidateReranker() {
          /** {@code rerank} の条件を再現するための補助情報を返す。 */
          public <T> List<T> rerank(
              String query, List<T> candidates, java.util.function.Function<T, String> text) {
            return List.of();
          }
        };
    selector.semanticSearch(
        new SemanticSkillSearch(config(), () -> model, () -> invalid, System::nanoTime));
    assertEquals(
        "shell",
        selector
            .selectCandidates(
                "compose",
                List.of(skill("shell", "commands", true), skill("writer", "articles", true)),
                1)
            .getFirst()
            .skill()
            .name());
  }
}
