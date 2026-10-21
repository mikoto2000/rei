package dev.mikoto2000.rei.reminder.command;

import dev.mikoto2000.rei.reminder.Reminder;
import dev.mikoto2000.rei.reminder.ReminderService;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** リマインドを操作しますを行うための利用者コマンド。 */
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
  /** リマインダーの管理のうち、レポートの操作を利用者コマンドとして受け付ける。 */
  private abstract static class ReportCommand implements Runnable {
    /** 出力先。 */
    protected java.io.PrintWriter out = new java.io.PrintWriter(System.out, true);
  }

  /** リマインダーの管理で用いるレポートを返す。 */
  private static String report(ReportCommand command) {
    var text = new java.io.StringWriter();
    command.out = new java.io.PrintWriter(text, true);
    command.run();
    return text.toString().replaceAll("[\\r\\n]+$", "");
  }

  /** リマインダーの管理で用いるレポート一覧を返す。 */
  public static String reportList(ReminderService service) {
    return report(new ListCommand(service));
  }

  /** リマインダーの管理で用いるレポート追加を返す。 */
  public static String reportAdd(
      ReminderService service,
      String message,
      OffsetDateTime at,
      OffsetDateTime target,
      Integer minutes) {
    var command = new AddCommand(service);
    command.messageParts = new String[] {message};
    command.at = at;
    command.target = target;
    command.minutesBefore = minutes;
    return report(command);
  }

  /** リマインダーの管理で用いるレポート削除を返す。 */
  public static String reportDelete(ReminderService service, long id) {
    var command = new DeleteCommand(service);
    command.id = id;
    return report(command);
  }

  /** リマインドを追加しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "add", description = "リマインドを追加します")
  public static class AddCommand extends ReportCommand {

    /** リマインダーサービス。 */
    private final ReminderService reminderService;

    /** 日時。 */
    @Option(names = "--at", description = "指定日時。形式: 2026-03-27T09:00:00Z")
    OffsetDateTime at;

    /** 対象。 */
    @Option(names = "--target", description = "基準日時。形式: 2026-03-27T10:00:00Z")
    OffsetDateTime target;

    /** 分数変更前。 */
    @Option(names = "--minutes-before", description = "基準日時の何分前に通知するか")
    Integer minutesBefore;

    /** メッセージ部分一覧。 */
    @Parameters(arity = "1..*", paramLabel = "MESSAGE", description = "リマインド内容")
    String[] messageParts;

    /** リマインドを追加しますを行う。 */
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

      out.println("追加: " + created.id() + " | " + created.remindAt() + " | " + created.message());
    }
  }

  /** 未通知のリマインドを一覧しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "未通知のリマインドを一覧します")
  public static class ListCommand extends ReportCommand {

    /** リマインダーサービス。 */
    private final ReminderService reminderService;

    /** 未通知のリマインドを一覧しますを行う。 */
    @Override
    public void run() {
      List<Reminder> reminders = reminderService.listActive();
      if (reminders.isEmpty()) {
        out.println("未通知のリマインドはありません");
        return;
      }

      for (Reminder reminder : reminders) {
        out.println(
            reminder.id()
                + " | "
                + reminder.type()
                + " | "
                + reminder.remindAt()
                + " | "
                + reminder.message());
      }
    }
  }

  /** リマインドを削除しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "delete", description = "リマインドを削除します")
  public static class DeleteCommand extends ReportCommand {

    /** リマインダーサービス。 */
    private final ReminderService reminderService;

    /** 識別子。 */
    @Parameters(paramLabel = "ID", description = "リマインド ID")
    long id;

    /** リマインドを削除しますを行う。 */
    @Override
    public void run() {
      reminderService.delete(id);
      out.println("削除: " + id);
    }
  }
}
