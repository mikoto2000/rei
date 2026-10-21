package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.application.cli.CliCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 保存済み利用可能な項目の結果と状態の契約を検証するテスト。 */
class CachedCatalogTest {
  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void declaredHelpBypassesRequiredArgumentsAtAnyOptionPositionAndRespectsLiteralBoundary() {
    var help = new CliCatalog.Option(List.of("-h", "--help"), "Help", null, "0", false, List.of());
    var child =
        new CliCatalog.Command(
            "export",
            List.of(),
            "Export",
            List.of(help),
            List.of(new CliCatalog.Parameter("0", "PATH", "1", "", List.of(), "")),
            List.of());
    var root =
        new CliCatalog.Command(
            "capture", List.of(), "Capture", List.of(help), List.of(), List.of(child), false);
    var cache = new CachedCatalog(new CliCatalog(List.of(root)));
    assertTrue(
        cache
            .requestedHelp(List.of("/capture", "export", "--help"))
            .matches("(?s).*Usage:\\s+capture export.*"));
    assertTrue(
        cache
            .requestedHelp(List.of("/capture", "export", "-h", "file.txt"))
            .matches("(?s).*Usage:\\s+capture export.*"));
    assertNull(cache.requestedHelp(List.of("/capture", "export", "--", "--help")));
  }

  /** 表記の統一について、繰り返された根拠を保持することを確認する。 */
  @Test
  void normalizationPreservesRepeatedEvidenceAndZeroArityFlags() {
    var evidence =
        new CliCatalog.Option(
            List.of("--evidence"), "", null, "1", false, List.of(), "", "", true, ",");
    var flag = new CliCatalog.Option(List.of("--approve"), "", null, "0", false, List.of());
    var command =
        new CliCatalog.Command(
            "reflection",
            List.of(),
            "",
            List.of(evidence, flag),
            List.of(new CliCatalog.Parameter("0", "ACTION", "1", "", List.of(), "")),
            List.of());
    var cache = new CachedCatalog(new CliCatalog(List.of(command)));
    assertEquals(
        List.of("/reflection", "lesson-propose", "--evidence", "a", "--evidence", "b", "--approve"),
        cache.normalize(
            List.of(
                "/reflection",
                "--evidence",
                "a",
                "lesson-propose",
                "--evidence",
                "b",
                "--approve")));
  }

  /** 全体共通選択可能な設定を受け付けられることを確認する。 */
  @Test
  void acceptsGlobalOptionsBeforeActionsWithoutExecutingAnything() {
    var option = new CliCatalog.Option(List.of("--max-runs"), "", null, "1", false, List.of());
    var command =
        new CliCatalog.Command(
            "goal",
            List.of(),
            "",
            List.of(option),
            List.of(new CliCatalog.Parameter("0..*", "ARGS", "0..*", "", List.of(), "", true)),
            List.of());
    var cache = new CachedCatalog(new CliCatalog(List.of(command)));
    assertEquals(
        List.of("/goal", "create", "objective", "--max-runs", "5"),
        cache.normalize(List.of("/goal", "--max-runs=5", "create", "objective")));
    assertThrows(
        IllegalArgumentException.class,
        () -> cache.normalize(List.of("/goal", "list", "--unknown")));
    assertEquals(
        List.of("/goal", "--", "--literal"), cache.normalize(List.of("/goal", "--", "--literal")));
  }

  /** 使い方を必要とすることを確認する。 */
  @Test
  void endOfOptionsDisablesFlagCompletionAndBareContainersRequireHelp() {
    var option =
        new CliCatalog.Option(List.of("--mode"), "", null, "1", false, List.of("READ_ONLY"));
    var child = new CliCatalog.Command("list", List.of(), "", List.of(), List.of(), List.of());
    var parent =
        new CliCatalog.Command(
            "config",
            List.of(),
            "Configuration",
            List.of(option),
            List.of(),
            List.of(child),
            false);
    var cache = new CachedCatalog(new CliCatalog(List.of(parent)));
    assertTrue(cache.requiresSubcommand(List.of("/config")));
    assertFalse(cache.requiresSubcommand(List.of("/config", "list")));
    assertEquals(List.of(), cache.complete(List.of("/config", "--", "--m"), 2, "--m"));
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void optionsBeforeSubcommandsAndDeclaredPathMetadataRemainPure() {
    var option = new CliCatalog.Option(List.of("--verbose"), "", null, "0", false, List.of());
    var child =
        new CliCatalog.Command(
            "summary",
            List.of(),
            "",
            List.of(),
            List.of(
                new CliCatalog.Parameter(
                    "0", "DATE", "0..1", "", List.of("today", "yesterday"), "")),
            List.of());
    var root =
        new CliCatalog.Command(
            "activity", List.of(), "", List.of(option), List.of(), List.of(child));
    var cache = new CachedCatalog(new CliCatalog(List.of(root)));
    assertEquals(
        List.of("yesterday"),
        cache.complete(List.of("/activity", "--verbose", "summary", "y"), 3, "y"));
    var output =
        new CliCatalog.Option(
            List.of("--output"),
            "",
            null,
            "1",
            false,
            List.of(),
            "dev.mikoto2000.rei.core.completion.PathCompletionCandidates$Any");
    cache =
        new CachedCatalog(
            new CliCatalog(
                List.of(
                    new CliCatalog.Command(
                        "generate", List.of(), "", List.of(output), List.of(), List.of()))));
    assertEquals(
        java.util.Set.of("file-or-directory"),
        cache.pathTypes(List.of("/generate", "--output", "p"), 2, "p"));
  }

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void completesNestedAliasesOptionsAndEnumValuesEntirelyFromImmutableData() {
    var option =
        new CliCatalog.Option(
            List.of("--mode"), "Mode", "EXCLUSIVE", "1", false, List.of("EXCLUSIVE", "READ_ONLY"));
    var command =
        new CliCatalog.Command(
            "switch", List.of("resume"), "Select", List.of(option), List.of(), List.of());
    var root =
        new CliCatalog.Command(
            "session", List.of(), "Sessions", List.of(), List.of(), List.of(command));
    var cache = new CachedCatalog(new CliCatalog(List.of(root)));
    assertEquals(List.of("/session"), cache.complete(List.of("/s"), 0, "/s"));
    assertEquals(List.of("resume"), cache.complete(List.of("/session", "r"), 1, "r"));
    assertEquals(List.of("--mode"), cache.complete(List.of("/session", "resume", "--m"), 2, "--m"));
    assertEquals(
        List.of("READ_ONLY"), cache.complete(List.of("/session", "switch", "--mode", "R"), 3, "R"));
    assertTrue(cache.help(List.of("session", "resume")).contains("--mode"));
  }
}
