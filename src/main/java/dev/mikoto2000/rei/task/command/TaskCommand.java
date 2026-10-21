package dev.mikoto2000.rei.task.command;

import dev.mikoto2000.rei.task.Task;
import dev.mikoto2000.rei.task.TaskQuery;
import dev.mikoto2000.rei.task.TaskService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** タスクを操作しますを行うための利用者コマンド。 */
@Component
@Command(
    name = "task",
    description = "タスクを操作します",
    subcommands = {
      TaskCommand.AuthCommand.class,
      TaskCommand.AddCommand.class,
      TaskCommand.ListCommand.class,
      TaskCommand.DoneCommand.class,
      TaskCommand.DeleteCommand.class
    })
public class TaskCommand {
  /** タスクの管理のうち、レポートの操作を利用者コマンドとして受け付ける。 */
  private abstract static class ReportCommand implements Runnable {
    /** レポート出力。 */
    private java.io.PrintWriter reportOutput;

    /** タスクの管理で用いる出力先を返す。 */
    java.io.PrintWriter out() {
      return reportOutput == null ? new java.io.PrintWriter(System.out, true) : reportOutput;
    }
  }

  /** タスクの管理で用いるレポートを返す。 */
  private static String report(ReportCommand command) {
    var text = new java.io.StringWriter();
    command.reportOutput = new java.io.PrintWriter(text, true);
    command.run();
    return text.toString().replaceFirst("[\\r\\n]+$", "");
  }

  /** タスクの管理で用いるレポート追加を返す。 */
  public static String reportAdd(TaskService service, String title, LocalDate due, int priority) {
    var command = new AddCommand(service);
    command.titleParts = new String[] {title};
    command.dueDate = due;
    command.priority = priority;
    return report(command);
  }

  /** タスクの管理で用いるレポート一覧を返す。 */
  public static String reportList(TaskService service, Integer priority, LocalDate dueBefore) {
    var command = new ListCommand(service);
    command.priority = priority;
    command.dueBefore = dueBefore;
    return report(command);
  }

  /** タスクの管理で用いるレポート完了を返す。 */
  public static String reportDone(TaskService service, long id) {
    var command = new DoneCommand(service);
    command.id = id;
    return report(command);
  }

  /** タスクの管理で用いるレポート削除を返す。 */
  public static String reportDelete(TaskService service, long id) {
    var command = new DeleteCommand(service);
    command.id = id;
    return report(command);
  }

  /** タスクの管理で用いるレポート権限の確認を返す。 */
  public static String reportAuthorization(TaskService service) {
    return report(new AuthCommand(service));
  }

  /** Google Tasks への OAuth 認可を実行しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "auth", description = "Google Tasks への OAuth 認可を実行します")
  public static class AuthCommand extends ReportCommand {

    /** タスクサービス。 */
    private final TaskService taskService;

    /** Google Tasks への OAuth 認可を実行しますを行う。 */
    @Override
    public void run() {
      try {
        taskService.authorize();
        out().println("Google Tasks の認可が完了しました");
      } catch (Exception e) {
        throw new RuntimeException("Google Tasks の認可に失敗しました", e);
      }
    }
  }

  /** タスクを追加しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "add", description = "タスクを追加します")
  public static class AddCommand extends ReportCommand {

    /** タスクサービス。 */
    private final TaskService taskService;

    /** 期限日付。 */
    @Option(names = "--due", description = "期限。形式: yyyy-MM-dd")
    LocalDate dueDate;

    /** 優先度。 */
    @Option(names = "--priority", defaultValue = "3", description = "優先度。数値が小さいほど高優先")
    int priority;

    /** タイトル部分一覧。 */
    @Parameters(arity = "1..*", paramLabel = "TITLE", description = "タスク名")
    String[] titleParts;

    /** タスクを追加しますを行う。 */
    @Override
    public void run() {
      try {
        Task created = taskService.add(String.join(" ", titleParts), dueDate, priority);
        out().println("追加: " + created.id() + " | " + created.title());
      } catch (RuntimeException e) {
        out().println("[error] " + userFacingMessage(e, "Google Tasks へのタスク追加に失敗しました"));
      }
    }
  }

  /** 未完了タスクを一覧しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "未完了タスクを一覧します")
  public static class ListCommand extends ReportCommand {

    /** タスクサービス。 */
    private final TaskService taskService;

    /** 優先度。 */
    @Option(names = "--priority", description = "この優先度以下で絞り込みます")
    Integer priority;

    /** 期限変更前。 */
    @Option(names = "--due-before", description = "この日付以前の期限で絞り込みます。形式: yyyy-MM-dd")
    LocalDate dueBefore;

    /** 未完了タスクを一覧しますを行う。 */
    @Override
    public void run() {
      List<Task> tasks = taskService.listOpen(new TaskQuery(priority, dueBefore));
      if (tasks.isEmpty()) {
        out().println("未完了タスクはありません");
        return;
      }

      for (Task task : tasks) {
        String dueDate = task.dueDate() == null ? "" : task.dueDate().toString();
        out().println(task.id() + " | " + task.priority() + " | " + dueDate + " | " + task.title());
      }
    }
  }

  /** タスクを完了にしますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "done", description = "タスクを完了にします")
  public static class DoneCommand extends ReportCommand {

    /** タスクサービス。 */
    private final TaskService taskService;

    /** 識別子。 */
    @Parameters(paramLabel = "ID", description = "タスク ID")
    long id;

    /** タスクを完了にしますを行う。 */
    @Override
    public void run() {
      Task completed = taskService.complete(id);
      out().println("完了: " + completed.id() + " | " + completed.title());
    }
  }

  /** タスクを削除しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "delete", description = "タスクを削除します")
  public static class DeleteCommand extends ReportCommand {

    /** タスクサービス。 */
    private final TaskService taskService;

    /** 識別子。 */
    @Parameters(paramLabel = "ID", description = "タスク ID")
    long id;

    /** タスクを削除しますを行う。 */
    @Override
    public void run() {
      taskService.delete(id);
      out().println("削除: " + id);
    }
  }

  /** タスクの管理で用いる利用者Facingメッセージを返す。 */
  private static String userFacingMessage(Throwable error, String fallback) {
    Throwable root = rootCause(error);
    String message = root.getMessage();
    if (message == null || message.isBlank()) {
      message = error.getMessage();
    }
    if (message == null || message.isBlank()) {
      return fallback;
    }
    if (fallback.equals(message) || message.startsWith(fallback + ":")) {
      return message;
    }
    return fallback + ": " + message;
  }

  /** タスクの管理で用いる作業ルート原因を返す。 */
  private static Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
