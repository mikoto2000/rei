package dev.mikoto2000.rei.memory.command;

import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.memory.service.SleepService;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** 現在 Session の未処理 Turn から長期記憶を整理しますを行うための利用者コマンド。 */
@Component
@Command(
    name = "sleep",
    description = "現在 Session の未処理 Turn から長期記憶を整理します",
    subcommands = {
      SleepCommand.Preview.class,
      SleepCommand.Status.class,
      SleepCommand.History.class,
      SleepCommand.Requests.class,
      SleepCommand.CancelRequest.class,
      SleepCommand.Auto.class
    })
public class SleepCommand implements Callable<Integer> {
  /** サービス。 */
  private final SleepService service;

  /** 補助。 */
  private final MemoryCommandSupport support;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** キャンセル。 */
  private final CommandCancellationService cancellation;

  /** 自動。 */
  private dev.mikoto2000.rei.memory.service.AutoSleepService auto;

  /** 長期記憶の活用で使用する自動を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void setAuto(dev.mikoto2000.rei.memory.service.AutoSleepService auto) {
    this.auto = auto;
  }

  /** 仕様。 */
  @Spec CommandSpec spec;

  /** ターミナルへの表示先。 */
  private java.io.PrintWriter shellOutput;

  /**
   * 長期記憶の活用を利用するため、指定を補って共通の初期化へ引き渡す。
   *
   * <p>Picocli completion can inspect the command tree without a Spring context.
   */
  public SleepCommand() {
    this(null, null, null, null);
  }

  /** 指定された値や利用先を使って、SleepCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public SleepCommand(
      SleepService service,
      MemoryCommandSupport support,
      ProjectService projects,
      CommandCancellationService cancellation) {
    this.service = service;
    this.support = support;
    this.projects = projects;
    this.cancellation = cancellation;
  }

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(java.io.PrintWriter output) {
    shellOutput = output;
  }

  /** 長期記憶の活用で用いる出力先を返す。 */
  private java.io.PrintWriter out() {
    return shellOutput == null ? spec.commandLine().getOut() : shellOutput;
  }

  /** 現在 Session の未処理 Turn から長期記憶を整理しますを行う。 */
  @Override
  public Integer call() {
    return run(false);
  }

  /** 現在 Session の未処理 Turn から長期記憶を整理しますを行う。 */
  int run(boolean preview) {
    return execute(
        () -> {
          support.enabled();
          String session = projects.currentSessionId(), project = projects.currentContext().id();
          cancellation.begin(Thread.currentThread());
          try {
            out().println(preview ? "Sleep preview started..." : "Sleep started...");
            out().flush();
            return support.report(service.sleep(session, project, preview));
          } finally {
            cancellation.clear();
          }
        });
  }

  /** 要求された処理を実行する。 */
  int execute(java.util.function.Supplier<String> action) {
    try {
      if (support == null) throw new IllegalStateException("Memory runtime unavailable");
      out().println(action.get());
      return 0;
    } catch (RuntimeException error) {
      if (dev.mikoto2000.rei.core.chat.RunCancellation.isCancellation(error)
          || Thread.currentThread().isInterrupted()) {
        out()
            .println(
                "Sleep cancelled. Checkpoint unchanged. 原因: "
                    + dev.mikoto2000.rei.event.FailureMessages.describe(error));
        return 130;
      }
      out().println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(error));
      return 2;
    } finally {
      out().flush();
    }
  }

  /** 長期記憶の活用に必要な事前確認を扱う。 */
  @Command(name = "preview")
  public static class Preview implements Callable<Integer> {
    /** 親。 */
    @ParentCommand SleepCommand parent;

    /** 長期記憶の活用の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.run(true);
    }
  }

  /** 長期記憶の活用に必要な状態を扱う。 */
  @Command(name = "status")
  public static class Status implements Callable<Integer> {
    /** 親。 */
    @ParentCommand SleepCommand parent;

    /** 長期記憶の活用の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.execute(parent.support::status);
    }
  }

  /** 長期記憶の活用に必要な履歴を扱う。 */
  @Command(name = "history")
  public static class History implements Callable<Integer> {
    /** 親。 */
    @ParentCommand SleepCommand parent;

    /** 長期記憶の活用の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.execute(parent.support::history);
    }
  }

  /** 現在Projectの保存済みAuto Sleep要求を表示ための利用者コマンド。 */
  @Command(name = "requests", description = "現在Projectの保存済みAuto Sleep要求を表示")
  public static class Requests implements Callable<Integer> {
    /** 親。 */
    @ParentCommand SleepCommand parent;

    /** 現在Projectの保存済みAuto Sleep要求を表示。 */
    public Integer call() {
      return parent.execute(parent.support::autoSleepRequests);
    }
  }

  /** 確認したrevisionの未処理要求を取り消す。稼働中Sleepは停止しないを行うための利用者コマンド。 */
  @Command(name = "cancel-request", description = "確認したrevisionの未処理要求を取り消す。稼働中Sleepは停止しない")
  public static class CancelRequest implements Callable<Integer> {
    /** 親。 */
    @ParentCommand SleepCommand parent;

    /** 会話。 */
    @Parameters(index = "0")
    String session;

    /** 改訂番号。 */
    @Option(names = "--revision", required = true)
    long revision;

    /** 確認したrevisionの未処理要求を取り消す。稼働中Sleepは停止しないを行う。 */
    public Integer call() {
      return parent.execute(() -> parent.support.cancelAutoSleepRequest(session, revision));
    }
  }

  /** 長期記憶の活用に必要な自動を扱う。 */
  @Command(
      name = "auto",
      subcommands = {AutoStatus.class, AutoResume.class})
  public static class Auto {
    /** 親。 */
    @ParentCommand SleepCommand parent;
  }

  /** 長期記憶の活用に必要な自動状態を扱う。 */
  @Command(name = "status")
  public static class AutoStatus implements Callable<Integer> {
    /** 自動。 */
    @ParentCommand Auto auto;

    /** 長期記憶の活用の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      var p = auto.parent;
      return p.execute(
          () -> p.auto.status(p.projects.currentSessionId(), p.projects.currentContext().id()));
    }
  }

  /** 長期記憶の活用に必要な自動再開を扱う。 */
  @Command(name = "resume")
  public static class AutoResume implements Callable<Integer> {
    /** 自動。 */
    @ParentCommand Auto auto;

    /** 長期記憶の活用の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      var p = auto.parent;
      return p.execute(
          () -> p.auto.resume(p.projects.currentSessionId(), p.projects.currentContext().id()));
    }
  }
}
