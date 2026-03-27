package dev.mikoto2000.rei.reminder.command;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.reminder.Reminder;
import dev.mikoto2000.rei.reminder.ReminderService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(
    name = "reminder",
    description = "リマインドを操作します",
    subcommands = {
      ReminderCommand.AddCommand.class,
      ReminderCommand.ListCommand.class,
      ReminderCommand.DeleteCommand.class
    })
public class ReminderCommand {

  @Component
  @RequiredArgsConstructor
  @Command(name = "add", description = "リマインドを追加します")
  public static class AddCommand implements Runnable {

    private final ReminderService reminderService;

    @Option(names = "--at", description = "指定日時。形式: 2026-03-27T09:00:00Z")
    OffsetDateTime at;

    @Option(names = "--target", description = "基準日時。形式: 2026-03-27T10:00:00Z")
    OffsetDateTime target;

    @Option(names = "--minutes-before", description = "基準日時の何分前に通知するか")
    Integer minutesBefore;

    @Parameters(arity = "1..*", paramLabel = "MESSAGE", description = "リマインド内容")
    String[] messageParts;

    @Override
    public void run() {
      String message = String.join(" ", messageParts);
      Reminder created;
      if (at != null && target == null && minutesBefore == null) {
        created = reminderService.addAt(message, at);
      } else if (at == null && target != null && minutesBefore != null) {
        created = reminderService.addBefore(message, target, minutesBefore);
      } else {
        throw new IllegalArgumentException("--at か --target と --minutes-before の組み合わせを指定してください");
      }

      System.out.println("追加: " + created.id() + " | " + created.remindAt() + " | " + created.message());
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "未通知のリマインドを一覧します")
  public static class ListCommand implements Runnable {

    private final ReminderService reminderService;

    @Override
    public void run() {
      List<Reminder> reminders = reminderService.listActive();
      if (reminders.isEmpty()) {
        System.out.println("未通知のリマインドはありません");
        return;
      }

      for (Reminder reminder : reminders) {
        System.out.println(reminder.id() + " | " + reminder.type() + " | " + reminder.remindAt() + " | " + reminder.message());
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "delete", description = "リマインドを削除します")
  public static class DeleteCommand implements Runnable {

    private final ReminderService reminderService;

    @Parameters(paramLabel = "ID", description = "リマインド ID")
    long id;

    @Override
    public void run() {
      reminderService.delete(id);
      System.out.println("削除: " + id);
    }
  }
}
