package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class SkillCandidateSmokeTest {

  @Test
  void evaluatesRepresentativeRequests() {
    SkillCandidateSelector selector = new SkillCandidateSelector();
    List<AgentSkill> skills = skills();
    List<Case> cases = List.of(
        new Case("Rspress の plugin を作りたい", "rspress"),
        new Case("PowerShell で Invoke-WebRequest を使いたい", "powershell"),
        new Case("Spring Boot の REST API を作りたい", "spring-boot"),
        new Case("技術ブログの記事を書きたい", "technical-writing"),
        new Case("今日の夕食は何がよいですか", null));

    long totalNanos = 0;
    int totalCandidates = 0;
    for (Case testCase : cases) {
      long started = System.nanoTime();
      List<SkillCandidate> candidates = selector.selectCandidates(testCase.request(), skills, 5);
      long durationNanos = System.nanoTime() - started;
      totalNanos += durationNanos;
      totalCandidates += candidates.size();
      SkillCandidateEvaluation evaluation = SkillCandidateEvaluator.evaluate(candidates, testCase.actual());
      System.out.printf(java.util.Locale.ROOT,
          "smoke total=%d candidates=%d duration=%.3fms top=%s actual=%s top1=%s top3=%s top5=%s%n",
          skills.size(), candidates.size(), durationNanos / 1_000_000.0,
          candidates.stream().map(candidate -> candidate.skill().name() + ":" + candidate.score()).toList(),
          testCase.actual(), evaluation.top1Hit(), evaluation.top3Hit(), evaluation.top5Hit());
      if (testCase.actual() == null) {
        assertThat(evaluation.selected()).isFalse();
      } else {
        assertThat(evaluation.top1Hit()).isTrue();
      }
    }
    System.out.printf(java.util.Locale.ROOT, "smoke averageCandidates=%.2f averageDuration=%.3fms%n",
        totalCandidates / (double) cases.size(), totalNanos / (double) cases.size() / 1_000_000.0);
  }

  private List<AgentSkill> skills() {
    return List.of(
        skill("rspress", "Rspress v2 の設定、plugin 開発、directive、Markdown extension", "rspress", "plugin"),
        skill("powershell", "PowerShell と pwsh のコマンド、Windows shell automation",
            "invoke-webrequest", "pwsh", "powershell"),
        skill("spring-boot", "Spring Boot REST API と Java backend 開発", "spring boot", "rest api"),
        skill("technical-writing", "技術記事、技術ブログ、documentation の執筆", "技術記事", "技術ブログ"),
        skill("database", "SQL database schema と query の設計", "sql", "database"));
  }

  private AgentSkill skill(String name, String description, String... keywords) {
    return new AgentSkill(name, description, List.of(keywords), true, Path.of(name),
        Path.of(name, "SKILL.md"), "not searched");
  }

  private record Case(String request, String actual) {
  }
}
