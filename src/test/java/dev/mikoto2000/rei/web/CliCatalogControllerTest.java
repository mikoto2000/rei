package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CliCatalogControllerTest {
  @Test
  void declaredHelpMatchesPicocliForEveryDirectShellGrammar() {
    var catalog = new CliCatalogController().catalog();
    var root =
        dev.mikoto2000.rei.ui.shell.RootCommand.class.getAnnotation(
            picocli.CommandLine.Command.class);
    var factory =
        new picocli.CommandLine.IFactory() {
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
          new picocli.CommandLine(type, factory),
          new picocli.CommandLine(dev.mikoto2000.rei.application.cli.CliGrammar.describe(command)),
          failures,
          !name.equals("voice"));
    }
    assertEquals(java.util.List.of(), failures);
  }

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

  @Test
  void everyDeclaredCommandCanRenderHelpWithoutAnExecutor() {
    var catalog = new CliCatalogController().catalog();
    for (var command : catalog.commands()) {
      var spec = dev.mikoto2000.rei.application.cli.CliGrammar.describe(command);
      assertNull(spec.userObject());
      assertTrue(
          new picocli.CommandLine(spec).getUsageMessage().contains(command.name()), command.name());
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
