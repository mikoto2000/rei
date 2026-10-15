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
  private abstract static class ReportCommand implements Runnable {
    private java.io.PrintWriter reportOutput;

    java.io.PrintWriter out() {
      return reportOutput == null ? new java.io.PrintWriter(System.out, true) : reportOutput;
    }
  }

  private static String report(ReportCommand command) {
    var text = new java.io.StringWriter();
    command.reportOutput = new java.io.PrintWriter(text, true);
    command.run();
    return text.toString().replaceFirst("[\\r\\n]+$", "");
  }

  public static String reportAdd(TaskService service, String title, LocalDate due, int priority) {
    var command = new AddCommand(service);
    command.titleParts = new String[] {title};
    command.dueDate = due;
    command.priority = priority;
    return report(command);
  }

  public static String reportList(TaskService service, Integer priority, LocalDate dueBefore) {
    var command = new ListCommand(service);
    command.priority = priority;
    command.dueBefore = dueBefore;
    return report(command);
  }

  public static String reportDone(TaskService service, long id) {
    var command = new DoneCommand(service);
    command.id = id;
    return report(command);
  }

  public static String reportDelete(TaskService service, long id) {
    var command = new DeleteCommand(service);
    command.id = id;
    return report(command);
  }

  public static String reportAuthorization(TaskService service) {
    return report(new AuthCommand(service));
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "auth", description = "Google Tasks への OAuth 認可を実行します")
  public static class AuthCommand extends ReportCommand {

    private final TaskService taskService;

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

  @Component
  @RequiredArgsConstructor
  @Command(name = "add", description = "タスクを追加します")
  public static class AddCommand extends ReportCommand {

    private final TaskService taskService;

    @Option(names = "--due", description = "期限。形式: yyyy-MM-dd")
    LocalDate dueDate;

    @Option(names = "--priority", defaultValue = "3", description = "優先度。数値が小さいほど高優先")
    int priority;

    @Parameters(arity = "1..*", paramLabel = "TITLE", description = "タスク名")
    String[] titleParts;

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

  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "未完了タスクを一覧します")
  public static class ListCommand extends ReportCommand {

    private final TaskService taskService;

    @Option(names = "--priority", description = "この優先度以下で絞り込みます")
    Integer priority;

    @Option(names = "--due-before", description = "この日付以前の期限で絞り込みます。形式: yyyy-MM-dd")
    LocalDate dueBefore;

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

  @Component
  @RequiredArgsConstructor
  @Command(name = "done", description = "タスクを完了にします")
  public static class DoneCommand extends ReportCommand {

    private final TaskService taskService;

    @Parameters(paramLabel = "ID", description = "タスク ID")
    long id;

    @Override
    public void run() {
      Task completed = taskService.complete(id);
      out().println("完了: " + completed.id() + " | " + completed.title());
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "delete", description = "タスクを削除します")
  public static class DeleteCommand extends ReportCommand {

    private final TaskService taskService;

    @Parameters(paramLabel = "ID", description = "タスク ID")
    long id;

    @Override
    public void run() {
      taskService.delete(id);
      out().println("削除: " + id);
    }
  }

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

  private static Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
