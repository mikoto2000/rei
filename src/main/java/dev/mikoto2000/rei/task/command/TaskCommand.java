package dev.mikoto2000.rei.task.command;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.task.Task;
import dev.mikoto2000.rei.task.TaskQuery;
import dev.mikoto2000.rei.task.TaskService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(
    name = "task",
    description = "タスクを操作します",
    subcommands = {
      TaskCommand.AddCommand.class,
      TaskCommand.ListCommand.class,
      TaskCommand.DoneCommand.class,
      TaskCommand.DeleteCommand.class
    })
public class TaskCommand {

  @Component
  @RequiredArgsConstructor
  @Command(name = "add", description = "タスクを追加します")
  public static class AddCommand implements Runnable {

    private final TaskService taskService;

    @Option(names = "--due", description = "期限。形式: yyyy-MM-dd")
    LocalDate dueDate;

    @Option(names = "--priority", defaultValue = "3", description = "優先度。数値が小さいほど高優先")
    int priority;

    @Option(names = "--tag", description = "タグ", split = ",")
    List<String> tags = List.of();

    @Parameters(arity = "1..*", paramLabel = "TITLE", description = "タスク名")
    String[] titleParts;

    @Override
    public void run() {
      Task created = taskService.add(String.join(" ", titleParts), dueDate, priority, tags);
      System.out.println("追加: " + created.id() + " | " + created.title());
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "未完了タスクを一覧します")
  public static class ListCommand implements Runnable {

    private final TaskService taskService;

    @Option(names = "--priority", description = "この優先度以下で絞り込みます")
    Integer priority;

    @Option(names = "--tag", description = "タグで絞り込みます")
    String tag;

    @Option(names = "--due-before", description = "この日付以前の期限で絞り込みます。形式: yyyy-MM-dd")
    LocalDate dueBefore;

    @Override
    public void run() {
      List<Task> tasks = taskService.listOpen(new TaskQuery(priority, tag, dueBefore));
      if (tasks.isEmpty()) {
        System.out.println("未完了タスクはありません");
        return;
      }

      for (Task task : tasks) {
        String dueDate = task.dueDate() == null ? "" : task.dueDate().toString();
        String tags = task.tags().isEmpty() ? "" : String.join(",", task.tags());
        System.out.println(task.id() + " | " + task.priority() + " | " + dueDate + " | " + task.title() + " | " + tags);
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "done", description = "タスクを完了にします")
  public static class DoneCommand implements Runnable {

    private final TaskService taskService;

    @Parameters(paramLabel = "ID", description = "タスク ID")
    long id;

    @Override
    public void run() {
      Task completed = taskService.complete(id);
      System.out.println("完了: " + completed.id() + " | " + completed.title());
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "delete", description = "タスクを削除します")
  public static class DeleteCommand implements Runnable {

    private final TaskService taskService;

    @Parameters(paramLabel = "ID", description = "タスク ID")
    long id;

    @Override
    public void run() {
      taskService.delete(id);
      System.out.println("削除: " + id);
    }
  }
}
