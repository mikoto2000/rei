package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** スキル候補対象の選択条件の結果と状態の契約を検証するテスト。 */
class SkillCandidateSelectorTest {

  /** 検証に使う対象の選択条件。 */
  private final SkillCandidateSelector selector = new SkillCandidateSelector();

  /** スキルの利用で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void ranksSkillNameMatchHighestAndIgnoresCase() {
    List<SkillCandidate> candidates =
        selector.selectCandidates(
            "Rspress の plugin を作る",
            List.of(skill("other", "Rspress documentation"), skill("rspress", "site generator")),
            5);

    assertThat(candidates)
        .extracting(candidate -> candidate.skill().name())
        .containsExactly("rspress", "other");
    assertThat(candidates.getFirst().score()).isGreaterThan(candidates.getLast().score());
    assertThat(selector.selectCandidates("POWERSHELL", List.of(skill("powershell", "shell")), 5))
        .hasSize(1);
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void scoresExactAndPartialKeywordMatches() {
    AgentSkill exact = skill("powershell", "shell", "invoke-webrequest");
    AgentSkill partial = skill("network", "http client", "invoke-webrequest-command");

    assertThat(
            selector
                .selectCandidates("Invoke-WebRequest を使いたい", List.of(exact), 5)
                .getFirst()
                .score())
        .isGreaterThanOrEqualTo(5);
    assertThat(
            selector.selectCandidates("webrequest を使いたい", List.of(partial), 5).getFirst().score())
        .isEqualTo(2);
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void matchesDescriptionAndJapaneseSubstring() {
    assertThat(
            selector.selectCandidates(
                "REST API を作る", List.of(skill("spring", "Spring Boot REST API")), 5))
        .hasSize(1);
    assertThat(selector.selectCandidates("技術記事を書きたい", List.of(skill("writer", "技術記事")), 5))
        .hasSize(1);
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void excludesZeroScoreSkills() {
    assertThat(
            selector.selectCandidates(
                "天気", List.of(skill("powershell", "command shell", "pwsh")), 5))
        .isEmpty();
  }

  /** スキルの利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void limitsSortsAndBreaksTiesBySkillName() {
    List<AgentSkill> skills =
        List.of(
            skill("zeta", "common"),
            skill("alpha", "common"),
            skill("beta", "common"),
            skill("gamma", "common"),
            skill("delta", "common"),
            skill("epsilon", "common"));

    List<SkillCandidate> candidates = selector.selectCandidates("common", skills, 5);

    assertThat(candidates).hasSize(5);
    assertThat(candidates)
        .extracting(candidate -> candidate.skill().name())
        .containsExactly("alpha", "beta", "delta", "epsilon", "gamma");
    assertThat(candidates)
        .extracting(SkillCandidate::score)
        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
  }

  /** {@code skill} の条件を再現するための補助情報を返す。 */
  private AgentSkill skill(String name, String description, String... keywords) {
    return new AgentSkill(
        name,
        description,
        List.of(keywords),
        true,
        Path.of(name),
        Path.of(name).resolve("SKILL.md"),
        "instructions must not be searched");
  }
}
