package dev.mikoto2000.rei.core.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.project.ProjectService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(
    name = "project",
    description = "作業ディレクトリ(project)を管理します",
    subcommands = {
      ProjectCommand.AddCommand.class,
      ProjectCommand.RemoveCommand.class,
      ProjectCommand.ListCommand.class,
      ProjectCommand.CdCommand.class
    })
public class ProjectCommand {

  @Component
  @RequiredArgsConstructor
  @Command(name = "add", description = "作業ディレクトリを登録します")
  public static class AddCommand implements Runnable {
    private final ProjectService projectService;

    @Parameters(index = "0", paramLabel = "DIR", completionCandidates = DirectoryCandidates.class)
    String directory;

    @Override
    public void run() {
      Path added = projectService.add(directory);
      System.out.println("project added: " + added);
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "remove", description = "登録済み作業ディレクトリを削除します")
  public static class RemoveCommand implements Runnable {
    private final ProjectService projectService;

    @Parameters(index = "0", paramLabel = "DIR", completionCandidates = RegisteredProjectCandidates.class)
    String directory;

    @Override
    public void run() {
      Path removed = projectService.remove(directory);
      System.out.println("project removed: " + removed);
      System.out.println("current project: " + projectService.currentProject());
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "登録済み作業ディレクトリを一覧します")
  public static class ListCommand implements Runnable {
    private final ProjectService projectService;

    @Override
    public void run() {
      Path current = projectService.currentProject();
      for (Path project : projectService.list()) {
        System.out.println((project.equals(current) ? "* " : "  ") + project);
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "cd", description = "現在の作業ディレクトリを変更します")
  public static class CdCommand implements Runnable {
    private final ProjectService projectService;

    @Parameters(index = "0", paramLabel = "DIR", completionCandidates = RegisteredProjectCandidates.class)
    String directory;

    @Override
    public void run() {
      Path changed = projectService.cd(directory);
      System.out.println("current project: " + changed);
    }
  }

  public static class RegisteredProjectCandidates implements Iterable<String> {
    @Override
    public Iterator<String> iterator() {
      return ProjectService.registeredProjectPathStrings().iterator();
    }
  }

  public static class DirectoryCandidates implements Iterable<String> {
    @Override
    public Iterator<String> iterator() {
      Path base = ProjectService.currentProjectOrStartupDirectory();
      try (var stream = Files.list(base)) {
        List<String> directories = stream
            .filter(Files::isDirectory)
            .map(Path::toString)
            .sorted()
            .toList();
        return directories.iterator();
      } catch (Exception e) {
        return List.<String>of().iterator();
      }
    }
  }
}
