package dev.mikoto2000.rei.reminder;

import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** リマインダーの管理に必要なリマインダー実行する作業を扱う。 */
@Component
@RequiredArgsConstructor
public class ReminderJob {

  /** リマインダーサービス。 */
  private final ReminderService reminderService;

  /** 音通知通知サービス。 */
  private final SoundNotificationService soundNotificationService;

  /** 依頼された操作を進めるため、期限リマインダー一覧を実行する。 */
  @Scheduled(fixedDelayString = "${rei.reminder.poll-interval-ms:60000}")
  public void runDueReminders() {
    OffsetDateTime now = now();
    for (Reminder reminder : reminderService.findDue(now)) {
      System.out.println("リマインド | " + reminder.remindAt() + " | " + reminder.message());
      soundNotificationService.notify("リマインドです。" + reminder.message());
      reminderService.markNotified(reminder.id(), now);
    }
  }

  /** リマインダーの管理で用いる現在日時を返す。 */
  OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
