package dev.mikoto2000.rei.reminder;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReminderTools {

  private final ReminderService reminderService;

  @Tool(name = "reminderCreate", description = "リマインドを作成します。remindAt を指定するか、targetAt と minutesBefore を組み合わせて指定してください。日時は ISO-8601 形式です。")
  Reminder reminderCreate(String message, String remindAt, String targetAt, Integer minutesBefore) {
    if (remindAt != null && !remindAt.isBlank() && targetAt == null && minutesBefore == null) {
      return reminderService.addAt(message, OffsetDateTime.parse(remindAt));
    }
    if ((remindAt == null || remindAt.isBlank()) && targetAt != null && !targetAt.isBlank() && minutesBefore != null) {
      return reminderService.addBefore(message, OffsetDateTime.parse(targetAt), minutesBefore);
    }
    throw new IllegalArgumentException("remindAt か targetAt と minutesBefore の組み合わせを指定してください");
  }

  @Tool(name = "reminderList", description = "未通知のリマインドを一覧します")
  List<Reminder> reminderList() {
    return reminderService.listActive();
  }
}
