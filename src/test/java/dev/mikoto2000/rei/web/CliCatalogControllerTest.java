package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 軽量CLI利用可能な項目受付窓口の結果と状態の契約を検証するテスト。 */
class CliCatalogControllerTest {
  /** 実行用の固定操作名定義とカタログの候補が一致することを確認する。 */
  @Test
  void fixedActionChoicesMatchTheirDefinitionsThroughoutTheCommandTree() throws Exception {
    var catalog = new CliCatalogController().catalog();
    for (var command : catalog.commands()) compareActionChoices(command);
  }

  private static void compareActionChoices(
      dev.mikoto2000.rei.application.cli.CliCatalog.Command command) throws Exception {
    for (var parameter : command.parameters()) {
      if (parameter.candidateType().isEmpty()) continue;
      var type = Class.forName(parameter.candidateType());
      if (!dev.mikoto2000.rei.core.completion.FirstArgumentCandidates.class.isAssignableFrom(type))
        continue;
      var candidates = (Iterable<?>) type.getDeclaredConstructor().newInstance();
      var expected = new java.util.ArrayList<String>();
      candidates.forEach(value -> expected.add(value.toString()));
      assertEquals(expected, parameter.choices(), command.name());
    }
    for (var child : command.commands()) compareActionChoices(child);
  }

  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void declaredHelpMatchesPicocliForEveryDirectShellGrammar() {
    var catalog = new CliCatalogController().catalog();
    var root =
        dev.mikoto2000.rei.ui.shell.RootCommand.class.getAnnotation(
            picocli.CommandLine.Command.class);
    var factory =
        new picocli.CommandLine.IFactory() {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          public <K> K create(Class<K> type) {
            return org.mockito.Mockito.mock(type);
          }
        };
    var failures = new java.util.ArrayList<String>();
    for (var type : root.subcommands()) {
      var name = type.getAnnotation(picocli.CommandLine.Command.class).name();
      if (java.util.Set.of("paper", "material-review", "doctor").contains(name)) continue;
      var command =
          catalog.commands().stream()
              .filter(item -> item.name().equals(name))
              .findFirst()
              .orElseThrow();
      compareHelp(
          dev.mikoto2000.rei.application.cli.CommandHelp.install(
              new picocli.CommandLine(type, factory)),
          new picocli.CommandLine(dev.mikoto2000.rei.application.cli.CliGrammar.describe(command)),
          failures,
          !name.equals("voice"));
    }
    assertEquals(java.util.List.of(), failures);
  }

  /** {@code compareHelp} の条件を再現するため、検証用の操作や状態を扱う。 */
  private static void compareHelp(
      picocli.CommandLine expected,
      picocli.CommandLine actual,
      java.util.List<String> failures,
      boolean recurse) {
    if (!expected.getUsageMessage().equals(actual.getUsageMessage()))
      failures.add(
          expected.getCommandName()
              + "\nEXPECTED:\n"
              + expected.getUsageMessage()
              + "ACTUAL:\n"
              + actual.getUsageMessage());
    if (recurse)
      for (var child : new java.util.HashSet<>(expected.getSubcommands().values()))
        compareHelp(child, actual.getSubcommands().get(child.getCommandName()), failures, true);
  }

  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void everyDeclaredCommandCanRenderHelpWithoutAnExecutor() {
    var catalog = new CliCatalogController().catalog();
    for (var command : catalog.commands()) {
      var spec = dev.mikoto2000.rei.application.cli.CliGrammar.describe(command);
      assertNull(spec.userObject());
      assertTrue(
          new picocli.CommandLine(spec).getUsageMessage().contains(command.name()), command.name());
      verifyCatalogHelp(new picocli.CommandLine(spec));
    }
    var session =
        catalog.commands().stream()
            .filter(command -> command.name().equals("session"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        new picocli.CommandLine(new dev.mikoto2000.rei.ui.shell.SessionCommand()).getUsageMessage(),
        new picocli.CommandLine(dev.mikoto2000.rei.application.cli.CliGrammar.describe(session))
            .getUsageMessage());
  }

  private static void verifyCatalogHelp(picocli.CommandLine line) {
    var output = new java.io.StringWriter();
    line.setOut(new java.io.PrintWriter(output));
    line.setErr(new java.io.PrintWriter(output));
    assertEquals(0, line.execute("--help"), line.getCommandName() + "\n" + output);
    assertTrue(output.toString().contains("Usage:"));
    assertTrue(
        line.getCommandSpec().usageMessage().description().length > 0, line.getCommandName());
    for (var child : new java.util.HashSet<>(line.getSubcommands().values()))
      verifyCatalogHelp(child);
  }

  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void exposesOnlyDeclaredShellGrammarWithoutInstantiatingOrRunningCommands() {
    var catalog = new CliCatalogController().catalog();
    var declared =
        dev.mikoto2000.rei.ui.shell.RootCommand.class
            .getAnnotation(picocli.CommandLine.Command.class)
            .subcommands();
    assertEquals(
        java.util.Arrays.stream(declared)
            .map(type -> type.getAnnotation(picocli.CommandLine.Command.class).name())
            .sorted()
            .toList(),
        catalog.commands().stream()
            .map(dev.mikoto2000.rei.application.cli.CliCatalog.Command::name)
            .sorted()
            .toList());
    var session =
        catalog.commands().stream()
            .filter(command -> command.name().equals("session"))
            .findFirst()
            .orElseThrow();
    var resume =
        session.commands().stream()
            .filter(command -> command.name().equals("switch"))
            .findFirst()
            .orElseThrow();
    assertTrue(resume.aliases().contains("resume"));
    var history =
        catalog.commands().stream()
            .filter(command -> command.name().equals("history"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        history.options().stream().anyMatch(option -> option.names().contains("--project-id")));
    var paper =
        catalog.commands().stream()
            .filter(command -> command.name().equals("paper"))
            .findFirst()
            .orElseThrow();
    assertTrue(paper.options().stream().anyMatch(option -> option.names().contains("--limit")));
    assertTrue(
        paper.parameters().stream().anyMatch(parameter -> parameter.choices().contains("search")));
    var activity =
        catalog.commands().stream()
            .filter(command -> command.name().equals("activity"))
            .findFirst()
            .orElseThrow();
    var coaching =
        activity.commands().stream()
            .filter(command -> command.name().equals("coaching"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        coaching.parameters().stream()
            .anyMatch(parameter -> parameter.choices().contains("configure")));
    var image =
        catalog.commands().stream()
            .filter(command -> command.name().equals("image"))
            .findFirst()
            .orElseThrow();
    var generate = image.commands().getFirst();
    assertTrue(
        generate.options().stream()
            .anyMatch(
                option ->
                    option.names().contains("--output")
                        && option.candidateType().endsWith("$Any")));
  }
}
