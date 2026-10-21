package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Operationalコマンド一覧の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class OperationalCommandsTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void allOperationsAndCompletionAreAvailableWithoutLlm() {
    var ds = new org.sqlite.SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + dir.resolve("commands.db"));
    var toolkit =
        new ClassificationToolkit(
            new ActivityProperties(),
            new OperationalRules(dir.resolve("rules.yaml")),
            new ClassificationTelemetryRepository(ds),
            Clock.systemUTC());
    var root = new ActivityCommand();
    root.operationalToolkit(
        toolkit,
        new ClassificationRuleSuggestions(
            toolkit,
            (input, schema) -> {
              throw new IllegalStateException();
            }));
    var cli = new picocli.CommandLine(root);
    var output = new java.io.StringWriter();
    cli.setOut(new java.io.PrintWriter(output));
    for (String action : new String[] {"status", "reload", "unknowns", "rules", "suggest-rules"})
      assertEquals(0, cli.execute("classification", action));
    for (String action : new String[] {"uncertain", "suggest-rules"})
      assertEquals(0, cli.execute("behavior", action));
    assertEquals(0, cli.execute("classification", "unknowns", "--top", "1"));
    assertTrue(output.toString().contains("Classification"));
    assertTrue(
        java.util.stream.StreamSupport.stream(
                new ActivityCommand.ClassificationCommand.ClassificationCandidates().spliterator(),
                false)
            .toList()
            .contains("suggest-rules"));
    assertTrue(
        java.util.stream.StreamSupport.stream(
                new ActivityCommand.BehaviorCommand.BehaviorCandidates().spliterator(), false)
            .toList()
            .contains("uncertain"));
  }
}
