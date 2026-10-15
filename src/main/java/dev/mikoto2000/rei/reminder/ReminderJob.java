package dev.mikoto2000.rei.reminder;

import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReminderJob {

  private final ReminderService reminderService;
  private final SoundNotificationService soundNotificationService;

  @Scheduled(fixedDelayString = "${rei.reminder.poll-interval-ms:60000}")
  public void runDueReminders() {
    OffsetDateTime now = now();
    for (Reminder reminder : reminderService.findDue(now)) {
      System.out.println("リマインド | " + reminder.remindAt() + " | " + reminder.message());
      soundNotificationService.notify("リマインドです。" + reminder.message());
      reminderService.markNotified(reminder.id(), now);
    }
  }

  OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
