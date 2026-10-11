package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class CliCatalogControllerTest {
  @Test void exposesOnlyDeclaredShellGrammarWithoutInstantiatingOrRunningCommands() {
    var catalog=new CliCatalogController().catalog();assertEquals(47,catalog.commands().size());
    var session=catalog.commands().stream().filter(command->command.name().equals("session")).findFirst().orElseThrow();
    var resume=session.commands().stream().filter(command->command.name().equals("switch")).findFirst().orElseThrow();assertTrue(resume.aliases().contains("resume"));
    var history=catalog.commands().stream().filter(command->command.name().equals("history")).findFirst().orElseThrow();
    assertTrue(history.options().stream().anyMatch(option->option.names().contains("--project-id")));
    var paper=catalog.commands().stream().filter(command->command.name().equals("paper")).findFirst().orElseThrow();
    assertTrue(paper.options().stream().anyMatch(option->option.names().contains("--limit")));
    assertTrue(paper.parameters().stream().anyMatch(parameter->parameter.choices().contains("search")));
  }
}
