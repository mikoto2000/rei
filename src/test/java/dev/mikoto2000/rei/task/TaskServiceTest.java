package dev.mikoto2000.rei.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;

class TaskServiceTest {

  @Test
  void addThrowsWhenGoogleCalendarIsDisabled() {
    TaskService service = new TaskService(disabledCalendarProps());

    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> service.add("設計レビュー", LocalDate.of(2026, 3, 31), 2, List.of("backend")));

    assertEquals("Google Tasks へのタスク追加に失敗しました", ex.getMessage());
  }

  @Test
  void listOpenThrowsWhenGoogleCalendarIsDisabled() {
    TaskService service = new TaskService(disabledCalendarProps());

    IllegalStateException ex = assertThrows(IllegalStateException.class, service::listOpen);

    assertEquals("Google Tasks の一覧取得に失敗しました", ex.getMessage());
  }

  @Test
  void completeThrowsWhenGoogleCalendarIsDisabled() {
    TaskService service = new TaskService(disabledCalendarProps());

    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.complete(1L));

    assertEquals("Google Tasks の完了更新に失敗しました", ex.getMessage());
  }

  @Test
  void deleteThrowsWhenGoogleCalendarIsDisabled() {
    TaskService service = new TaskService(disabledCalendarProps());

    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.delete(1L));

    assertEquals("Google Tasks の削除に失敗しました", ex.getMessage());
  }

  private GoogleCalendarProperties disabledCalendarProps() {
    return new GoogleCalendarProperties(
        "Rei",
        "",
        "",
        new GoogleCalendarProperties.CalendarProperties(false, "primary", ""),
        new GoogleCalendarProperties.TaskProperties(true));
  }
}
