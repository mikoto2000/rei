package dev.mikoto2000.rei.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** タスクサービスの結果と状態の契約を検証するテスト。 */
class TaskServiceTest {

  /** 追加について、Googleタスク一覧を利用することを確認する。 */
  @Test
  void addUsesGoogleTasksEvenWhenGoogleCalendarIsDisabled() {
    TaskService service = new TaskService(disabledCalendarProps());

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> service.add("設計レビュー", LocalDate.of(2026, 3, 31), 2));

    assertEquals("Google Tasks へのタスク追加に失敗しました", ex.getMessage());
    assertTrue(
        ex.getCause().getMessage().startsWith("Google OAuth credentials file was not found:"));
  }

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void addThrowsWhenGoogleTasksIsDisabled() {
    TaskService service = new TaskService(disabledTaskProps());

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> service.add("設計レビュー", LocalDate.of(2026, 3, 31), 2));

    assertEquals("Google Tasks へのタスク追加に失敗しました", ex.getMessage());
    assertEquals("Google Task integration is disabled", ex.getCause().getMessage());
  }

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void listOpenThrowsWhenGoogleCalendarIsDisabled() {
    TaskService service = new TaskService(disabledCalendarProps());

    IllegalStateException ex = assertThrows(IllegalStateException.class, service::listOpen);

    assertEquals("Google Tasks の一覧取得に失敗しました", ex.getMessage());
    assertTrue(
        ex.getCause().getMessage().startsWith("Google OAuth credentials file was not found:"));
  }

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void completeThrowsWhenGoogleCalendarIsDisabled() {
    TaskService service = new TaskService(disabledCalendarProps());

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> service.complete(1L));

    assertEquals("Google Tasks の完了更新に失敗しました", ex.getMessage());
    assertTrue(
        ex.getCause().getMessage().startsWith("Google OAuth credentials file was not found:"));
  }

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void deleteThrowsWhenGoogleCalendarIsDisabled() {
    TaskService service = new TaskService(disabledCalendarProps());

    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.delete(1L));

    assertEquals("Google Tasks の削除に失敗しました", ex.getMessage());
    assertTrue(
        ex.getCause().getMessage().startsWith("Google OAuth credentials file was not found:"));
  }

  /** {@code disabledCalendarProps} の条件を再現するための補助情報を返す。 */
  private GoogleCalendarProperties disabledCalendarProps() {
    return new GoogleCalendarProperties(
        "Rei",
        "missing-google-credentials.json",
        "missing-google-tokens",
        new GoogleCalendarProperties.CalendarProperties(false, "primary", ""),
        new GoogleCalendarProperties.TaskProperties(true));
  }

  /** {@code disabledTaskProps} の条件を再現するための補助情報を返す。 */
  private GoogleCalendarProperties disabledTaskProps() {
    return new GoogleCalendarProperties(
        "Rei",
        "missing-google-credentials.json",
        "missing-google-tokens",
        new GoogleCalendarProperties.CalendarProperties(true, "primary", ""),
        new GoogleCalendarProperties.TaskProperties(false));
  }
}
