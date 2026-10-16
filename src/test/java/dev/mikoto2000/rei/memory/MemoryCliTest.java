package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.project.ProjectClientScope;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.memory.command.MemoryCommand;
import dev.mikoto2000.rei.memory.command.MemoryCommandSupport;
import dev.mikoto2000.rei.memory.command.SleepCommand;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.MemoryResolutionModel;
import dev.mikoto2000.rei.memory.service.MemoryResolver;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.service.SleepService;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import picocli.CommandLine;

@org.junit.jupiter.api.Tag("integration")
class MemoryCliTest {
  @TempDir Path dir;

  @Test
  void manualSleepAndMemoryCommandsAreReachable() {
    var props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null);
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("m.db"));
    var legacy = new MemoryService(ds, props);
    var repo = new MemoryRepository(ds, legacy);
    var projects = new ProjectService(dir, new ProjectRegistry(dir.resolve("projects.json")));
    var service =
        new SleepService(
            repo,
            ConversationTurnStore.inMemory(),
            t -> java.util.List.of(),
            new MemoryResolver(mock(MemoryResolutionModel.class), props),
            props);
    var support = new MemoryCommandSupport(repo, service, projects, props);
    try (var scope = ProjectClientScope.open(projects.newClient())) {
      projects.selectSession("s");
      var sleep =
          new CommandLine(
              new SleepCommand(service, support, projects, new CommandCancellationService()));
      var output = new StringWriter();
      sleep.setOut(new PrintWriter(output, true));
      for (String arg : new String[] {"preview", "status", "history", ""})
        assertEquals(0, arg.isEmpty() ? sleep.execute() : sleep.execute(arg), output.toString());
      assertTrue(output.toString().contains("unslept turns"));
      var memory =
          repo.insert(LongTermMemoryTest.candidate("Vision first", MemoryScope.GLOBAL), null, "s");
      var foreign =
          repo.insert(
              LongTermMemoryTest.candidate("Other project", MemoryScope.PROJECT), "other", "s");
      var selected = projects.currentContext().id();
      assertFalse(support.listForProject(selected, 20, 0).contains("Other project"));
      assertThrows(
          IllegalArgumentException.class, () -> support.showForProject(selected, foreign.id()));
      assertThrows(
          IllegalArgumentException.class, () -> support.forgetForProject(selected, foreign.id()));
      assertEquals(MemoryStatus.ACTIVE, repo.find(foreign.id()).orElseThrow().status());
      var root = new MemoryCommand();
      var cli =
          new CommandLine(
              root,
              new CommandLine.IFactory() {
                public <K> K create(Class<K> cls) throws Exception {
                  Object command;
                  if (cls == MemoryCommand.ListCommand.class) {
                    var c = new MemoryCommand.ListCommand(legacy);
                    c.setSupport(support);
                    command = c;
                  } else if (cls == MemoryCommand.SearchCommand.class) {
                    var c = new MemoryCommand.SearchCommand(legacy);
                    c.setSupport(support);
                    command = c;
                  } else if (cls == MemoryCommand.ForgetCommand.class) {
                    var c = new MemoryCommand.ForgetCommand(legacy);
                    c.setSupport(support);
                    command = c;
                  } else if (cls == MemoryCommand.ShowCommand.class)
                    command = new MemoryCommand.ShowCommand(support);
                  else return mock(cls);
                  return cls.cast(command);
                }
              });
      assertEquals(0, cli.execute("list"));
      assertEquals(0, cli.execute("search", "Vision", "first"));
      assertEquals(0, cli.execute("show", memory.id()));
      assertEquals(0, cli.execute("forget", memory.id()));
      assertEquals(MemoryStatus.ARCHIVED, repo.find(memory.id()).orElseThrow().status());
    }
  }
}
