package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mikoto2000.rei.core.project.ProjectService;
import picocli.CommandLine;

class ProjectCommandTest {

  @TempDir
  Path tempDir;

  @Test
  void projectCommandAddsListsChangesAndRemovesProject() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    ProjectService service = new ProjectService(tempDir, tempDir.resolve(".rei").resolve("projects"));
    CommandLine commandLine = newCommand(service);

    assertEquals(0, commandLine.execute("add", project.toString()));
    assertEquals(0, commandLine.execute("cd", project.toString()));
    assertEquals(project.toAbsolutePath().normalize(), service.currentProject());
    assertEquals(0, commandLine.execute("remove", project.toString()));
    assertEquals(tempDir.toAbsolutePath().normalize(), service.currentProject());
  }

  @Test
  void listMarksCurrentProject() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    ProjectService service = new ProjectService(tempDir, tempDir.resolve(".rei").resolve("projects"));
    service.add(project.toString());
    service.cd(project.toString());

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertEquals(0, newCommand(service).execute("list"));
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("* " + project.toAbsolutePath().normalize()));
  }

  private CommandLine newCommand(ProjectService service) {
    return new CommandLine(new ProjectCommand(), new CommandLine.IFactory() {
      @Override
      public <K> K create(Class<K> cls) throws Exception {
        if (cls == ProjectCommand.AddCommand.class) {
          return cls.cast(new ProjectCommand.AddCommand(service));
        }
        if (cls == ProjectCommand.RemoveCommand.class) {
          return cls.cast(new ProjectCommand.RemoveCommand(service));
        }
        if (cls == ProjectCommand.ListCommand.class) {
          return cls.cast(new ProjectCommand.ListCommand(service));
        }
        if (cls == ProjectCommand.CdCommand.class) {
          return cls.cast(new ProjectCommand.CdCommand(service));
        }
        return CommandLine.defaultFactory().create(cls);
      }
    });
  }
}
