package dev.mikoto2000.rei.workcontext;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** プロジェクトの作業引き継ぎを行うための利用者コマンド。 */
@Component
@Command(
    name = "work",
    description = "プロジェクトの作業引き継ぎ",
    subcommands = {WorkCommand.Show.class, WorkCommand.Update.class, WorkCommand.History.class})
public class WorkCommand implements Callable<Integer> {
  /** サービス。 */
  private final WorkContextService service;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** Gitの情報。 */
  private final WorkContextGit git;

  /** キャンセル。 */
  private final CommandCancellationService cancellation;

  /** 仕様。 */
  @Spec CommandSpec spec;

  /** ターミナルへの表示先。 */
  private java.io.PrintWriter shellOutput;

  /** 作業文脈の引き継ぎを利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WorkCommand() {
    this(null, null, null, null);
  }

  /** 指定された値や利用先を使って、WorkCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public WorkCommand(
      WorkContextService service,
      ProjectService projects,
      WorkContextGit git,
      CommandCancellationService cancellation) {
    this.service = service;
    this.projects = projects;
    this.git = git;
    this.cancellation = cancellation;
  }

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(java.io.PrintWriter writer) {
    shellOutput = writer;
  }

  /** 作業文脈の引き継ぎで用いる出力先を返す。 */
  private java.io.PrintWriter out() {
    return shellOutput == null ? spec.commandLine().getOut() : shellOutput;
  }

  /** プロジェクトの作業引き継ぎを行う。 */
  @Override
  public Integer call() {
    return show();
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  int show() {
    return execute(
        () -> {
          var p = projects.currentContext();
          return new WorkContextFormatter()
              .details(
                  service.current(p.id()).orElse(null),
                  git.capture(p.root(), java.time.Instant.now()));
        });
  }

  /** 指定された変更を、管理中の情報へ反映する。 */
  int update() {
    return execute(
        () -> {
          // Capture session first; the application service resolves the immutable session owner.
          String session = projects.currentSessionId();
          cancellation.begin(Thread.currentThread());
          try {
            return service
                .update(session, null, true)
                .map(
                    c ->
                        "Work Context saved: project="
                            + c.projectId()
                            + " revision="
                            + c.revision())
                .orElse("保存できる会話はまだありません。");
          } finally {
            cancellation.clear();
          }
        });
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  int history() {
    return execute(
        () -> {
          var rows = service.history(projects.currentContext().id(), 100);
          if (rows.isEmpty()) return "Work Context: 履歴はまだありません。";
          return rows.stream()
              .map(
                  c ->
                      "revision="
                          + c.revision()
                          + " updated="
                          + c.updatedAt()
                          + " items="
                          + c.items().size())
              .collect(java.util.stream.Collectors.joining("\n"));
        });
  }

  /** 要求された処理を実行する。 */
  int execute(java.util.function.Supplier<String> action) {
    try {
      if (service == null) throw new IllegalStateException("Work Context runtime unavailable");
      out().println(action.get());
      return 0;
    } catch (WorkContextExtractionException error) {
      out().println("[error] " + error.diagnostic().display());
      return 2;
    } catch (RuntimeException error) {
      if (RunCancellation.isCancellation(error) || Thread.currentThread().isInterrupted()) {
        out()
            .println(
                "Work Context update cancelled. Saved state retained. 原因: "
                    + dev.mikoto2000.rei.event.FailureMessages.describe(error));
        return 130;
      }
      out().println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(error));
      return 2;
    } finally {
      out().flush();
    }
  }

  /** 作業文脈の引き継ぎに必要な表示を扱う。 */
  @Command(name = "show", description = "現在の作業文脈を表示します")
  public static class Show implements Callable<Integer> {
    /** 親。 */
    @ParentCommand WorkCommand parent;

    /** 作業文脈の引き継ぎの要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.show();
    }
  }

  /** 作業文脈の引き継ぎに必要な更新を扱う。 */
  @Command(name = "update", description = "現在の作業文脈を更新します")
  public static class Update implements Callable<Integer> {
    /** 親。 */
    @ParentCommand WorkCommand parent;

    /** 作業文脈の引き継ぎの要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.update();
    }
  }

  /** 作業文脈の引き継ぎに必要な履歴を扱う。 */
  @Command(name = "history", description = "作業文脈の更新履歴を表示します")
  public static class History implements Callable<Integer> {
    /** 親。 */
    @ParentCommand WorkCommand parent;

    /** 作業文脈の引き継ぎの要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.history();
    }
  }
}
