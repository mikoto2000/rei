package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.file.Path;
import java.util.Iterator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** 作業ディレクトリ(project)を管理しますを行うための利用者コマンド。 */
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

  /** 作業ディレクトリを登録しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "add", description = "作業ディレクトリを登録します")
  public static class AddCommand implements Runnable {
    /** プロジェクトサービス。 */
    private final ProjectService projectService;

    /** ディレクトリ。 */
    @Parameters(
        index = "0",
        paramLabel = "DIR",
        completionCandidates =
            dev.mikoto2000.rei.core.completion.PathCompletionCandidates.Directory.class)
    String directory;

    /** 作業ディレクトリを登録しますを行う。 */
    @Override
    public void run() {
      Path added = projectService.add(directory);
      System.out.println("project added: " + added);
    }
  }

  /** 登録済み作業ディレクトリを削除しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "remove", description = "登録済み作業ディレクトリを削除します")
  public static class RemoveCommand implements Runnable {
    /** プロジェクトサービス。 */
    private final ProjectService projectService;

    /** ディレクトリ。 */
    @Parameters(
        index = "0",
        paramLabel = "DIR",
        completionCandidates = RegisteredProjectCandidates.class)
    String directory;

    /** 登録済み作業ディレクトリを削除しますを行う。 */
    @Override
    public void run() {
      Path removed = projectService.remove(directory);
      System.out.println("project removed: " + removed);
      System.out.println("current project: " + projectService.currentProject());
    }
  }

  /** 登録済み作業ディレクトリを一覧しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "登録済み作業ディレクトリを一覧します")
  public static class ListCommand implements Runnable {
    /** プロジェクトサービス。 */
    private final ProjectService projectService;

    /** 登録済み作業ディレクトリを一覧しますを行う。 */
    @Override
    public void run() {
      Path current = projectService.currentProject();
      for (Path project : projectService.list()) {
        System.out.println((project.equals(current) ? "* " : "  ") + project);
      }
    }
  }

  /** 現在の作業ディレクトリを変更しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "cd", description = "現在の作業ディレクトリを変更します")
  public static class CdCommand implements Runnable {
    /** プロジェクトサービス。 */
    private final ProjectService projectService;

    /** ディレクトリ。 */
    @Parameters(
        index = "0",
        paramLabel = "DIR",
        completionCandidates = ProjectDirectoryCandidates.class)
    String directory;

    /** 現在の作業ディレクトリを変更しますを行う。 */
    @Override
    public void run() {
      Path changed = projectService.cd(directory);
      System.out.println("current project: " + changed);
    }
  }

  /** 登録済みプロジェクトで利用できる入力候補を提供する。 */
  public static class RegisteredProjectCandidates implements Iterable<String> {
    /** 利用可能な候補を順に参照するための窓口を返す。 */
    @Override
    public Iterator<String> iterator() {
      return ProjectService.completionProjectPathStrings().iterator();
    }
  }

  /** プロジェクトディレクトリで利用できる入力候補を提供する。 */
  public static class ProjectDirectoryCandidates extends RegisteredProjectCandidates
      implements dev.mikoto2000.rei.core.completion.CompletionMetadata {
    /** 共通の実行基盤で用いる種別一覧を返す。 */
    public java.util.Set<String> types(
        dev.mikoto2000.rei.core.completion.CompletionContext context) {
      return java.util.Set.of("choices", "directory");
    }

    /** 共通の実行基盤で用いる選択肢を返す。 */
    public java.util.List<dev.mikoto2000.rei.core.completion.CompletionCandidate> choices(
        dev.mikoto2000.rei.core.completion.CompletionContext context) {
      return ProjectService.completionProjectPathStrings().stream()
          .map(
              path ->
                  new dev.mikoto2000.rei.core.completion.CompletionCandidate(
                      path, path, "Registered project", "project", true))
          .toList();
    }
  }
}
