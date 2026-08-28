package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class SkillCandidateSelectorTest {

  private final SkillCandidateSelector selector = new SkillCandidateSelector();

  @Test
  void ranksSkillNameMatchHighestAndIgnoresCase() {
    List<SkillCandidate> candidates = selector.selectCandidates("Rspress の plugin を作る",
        List.of(skill("other", "Rspress documentation"), skill("rspress", "site generator")), 5);

    assertThat(candidates).extracting(candidate -> candidate.skill().name()).containsExactly("rspress", "other");
    assertThat(candidates.getFirst().score()).isGreaterThan(candidates.getLast().score());
    assertThat(selector.selectCandidates("POWERSHELL", List.of(skill("powershell", "shell")), 5)).hasSize(1);
  }

  @Test
  void scoresExactAndPartialKeywordMatches() {
    AgentSkill exact = skill("powershell", "shell", "invoke-webrequest");
    AgentSkill partial = skill("network", "http client", "invoke-webrequest-command");

    assertThat(selector.selectCandidates("Invoke-WebRequest を使いたい", List.of(exact), 5).getFirst().score())
        .isGreaterThanOrEqualTo(5);
    assertThat(selector.selectCandidates("webrequest を使いたい", List.of(partial), 5).getFirst().score())
        .isEqualTo(2);
  }

  @Test
  void matchesDescriptionAndJapaneseSubstring() {
    assertThat(selector.selectCandidates("REST API を作る", List.of(skill("spring", "Spring Boot REST API")), 5))
        .hasSize(1);
    assertThat(selector.selectCandidates("技術記事を書きたい", List.of(skill("writer", "技術記事")), 5))
        .hasSize(1);
  }

  @Test
  void excludesZeroScoreSkills() {
    assertThat(selector.selectCandidates("天気", List.of(skill("powershell", "command shell", "pwsh")), 5))
        .isEmpty();
  }

  @Test
  void limitsSortsAndBreaksTiesBySkillName() {
    List<AgentSkill> skills = List.of(
        skill("zeta", "common"), skill("alpha", "common"), skill("beta", "common"),
        skill("gamma", "common"), skill("delta", "common"), skill("epsilon", "common"));

    List<SkillCandidate> candidates = selector.selectCandidates("common", skills, 5);

    assertThat(candidates).hasSize(5);
    assertThat(candidates).extracting(candidate -> candidate.skill().name())
        .containsExactly("alpha", "beta", "delta", "epsilon", "gamma");
    assertThat(candidates).extracting(SkillCandidate::score).isSortedAccordingTo(java.util.Comparator.reverseOrder());
  }

  private AgentSkill skill(String name, String description, String... keywords) {
    return new AgentSkill(name, description, List.of(keywords), true, Path.of(name),
        Path.of(name).resolve("SKILL.md"), "instructions must not be searched");
  }
}
