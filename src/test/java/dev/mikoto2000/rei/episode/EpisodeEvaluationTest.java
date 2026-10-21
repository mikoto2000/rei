package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.MemoryCandidate;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.MemoryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 経験の記録評価の結果と状態の契約を検証するテスト。
 *
 * <p>Deterministic retrieval-only comparison. It does not pretend to measure answer correctness.
 */
@Tag("integration")
class EpisodeEvaluationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 過去の経験の活用で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void recordsLegacyBaselineAndEpisodeRetrievalForSevenQuestions() throws Exception {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db"));
    var props = new MemoryProperties(true, 0, 0, 0, 0, 0, 0, null);
    var memory = new MemoryRepository(ds, new MemoryService(ds, props));
    memory.insert(
        new MemoryCandidate(
            MemoryType.DECISION,
            MemoryScope.PROJECT,
            "CLI を別プロセスにする",
            "CLI の方針",
            .9,
            .9,
            List.of("r"),
            List.of()),
        "p",
        "s");
    var repository = new EpisodeRepository(ds);
    repository.saveBatch(
        "s",
        0,
        1,
        List.of(
            new Episode(
                "e",
                "p",
                "s",
                "v",
                "2026-10-10T00:00:00Z",
                Episode.Status.UNVERIFIED,
                "CLI 設計変更",
                "CLI を別プロセスにする案を検討。実装結果は未確認。",
                List.of(
                    new Episode.Claim(
                        "reason", "障害の分離", Episode.Evidence.DERIVED_SUMMARY, "r", "user"),
                    new Episode.Claim(
                        "alternative", "同一プロセス", Episode.Evidence.MODEL_PROPOSAL, "r", "assistant"),
                    new Episode.Claim(
                        "unknown", "性能と実装結果は未確認", Episode.Evidence.UNVERIFIED, "r", "assistant")),
                .8)));
    var questions =
        List.of(
            "以前 CLI を別プロセスにした理由は？",
            "その設計では何を代替案として検討した？",
            "以前の方針から何が変更された？",
            "その情報の原典は？",
            "昨日決めたことを教えて",
            "その機能は実装済み？ それとも提案だけ？",
            "先月どのような設計変更をした？");
    var report =
        new StringBuilder(
            "# Episode retrieval fixture\n\nScope: unchanged legacy long-term memory retrieval versus episode lexical retrieval. Both receive explicit CLI context. One synthetic event; dates are query text, not tested filters. No LLM calls.\n\n|Question|Legacy candidates|Episode candidates|Legacy microseconds|Episode microseconds|\n|---|---:|---:|---:|---:|\n");
    for (String question : questions) {
      String query = question + " CLI";
      long start = System.nanoTime();
      var baseline = memory.search(query, "p", 8);
      long legacyTime = System.nanoTime() - start;
      start = System.nanoTime();
      var enhanced = repository.search(query, "p", 8);
      long episodeTime = System.nanoTime() - start;
      report
          .append("|")
          .append(question)
          .append("|")
          .append(baseline.size())
          .append("|")
          .append(enhanced.size())
          .append("|")
          .append(legacyTime / 1000)
          .append("|")
          .append(episodeTime / 1000)
          .append("|\n");
      assertEquals(1, enhanced.size());
      assertEquals(Episode.Evidence.MODEL_PROPOSAL, enhanced.getFirst().claims().get(1).evidence());
    }
    report.append(
        "\nAnswer accuracy, evidence match rate, stale-answer rate, unslept conversation success, actual model tokens and physical SQLite I/O: 未測定. Timings are single-run local observations, not a statistical benchmark.\n");
    Files.createDirectories(Path.of("target"));
    Files.writeString(Path.of("target/episode-evaluation.md"), report.toString());
  }
}
