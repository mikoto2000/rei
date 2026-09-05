package dev.mikoto2000.rei.reminder;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReminderTools {

  private final ReminderService reminderService;

  @Tool(name = "reminderCreate", description = "リマインドを作成します。remindAt を指定するか、targetAt と minutesBefore を組み合わせて指定してください。使わない引数は省略するか null にしてください。日時はタイムゾーン付き ISO-8601 形式（例: 2026-09-07T09:00:00+09:00）です。")
  Reminder reminderCreate(
      @ToolParam(description = "通知するメッセージ") String message,
      @ToolParam(required = false, description = "通知日時。指定時は targetAt と minutesBefore を省略する") String remindAt,
      @ToolParam(required = false, description = "対象日時。remindAt を省略し、minutesBefore と組み合わせる") String targetAt,
      @ToolParam(required = false, description = "対象日時の何分前に通知するか。targetAt と組み合わせる。remindAt 指定時は省略する") Integer minutesBefore) {
    IO.println(String.format("リマインドを作成するよ。message=%s、remindAt=%s、targetAt=%s、minutesBefore=%s",
        message, remindAt, targetAt, minutesBefore));
    if (remindAt != null && !remindAt.isBlank() && (targetAt == null || targetAt.isBlank()) && minutesBefore == null) {
      return reminderService.addAt(message, OffsetDateTime.parse(remindAt));
    }
    if ((remindAt == null || remindAt.isBlank()) && targetAt != null && !targetAt.isBlank() && minutesBefore != null) {
      return reminderService.addBefore(message, OffsetDateTime.parse(targetAt), minutesBefore);
    }
    throw new IllegalArgumentException("remindAt か targetAt と minutesBefore の組み合わせを指定してください");
  }

  @Tool(name = "reminderList", description = "未通知のリマインドを一覧します")
  List<Reminder> reminderList() {
    IO.println("未通知のリマインドを一覧するよ");
    return reminderService.listActive();
  }
}
