package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.googlecalendar.*;
import dev.mikoto2000.rei.googlecalendar.command.ScheduleCommand;
import dev.mikoto2000.rei.task.*;
import dev.mikoto2000.rei.task.command.TaskCommand;

class GoogleCommandReportsTest {
  @Test void calendarDateTakesPrecedenceAndPreservesShellFormatting() throws Exception {
    var service=mock(GoogleCalendarService.class);var date=LocalDate.of(2026,10,11);
    when(service.listEventsForDate(date)).thenReturn(List.of(new GoogleCalendarEventSummary("event","Meeting","09:00","10:00","Desk","confirmed")));
    assertEquals("event | 09:00 | 10:00 | Meeting | Desk",ScheduleCommand.reportList(service,date,"ignored","ignored"));
    verify(service).listEventsForDate(date);verify(service,never()).parseDateTime(anyString());
  }
  @Test void taskReportsKeepDefaultsFilteringAndMessages() {
    var service=mock(TaskService.class);var task=new Task(12,"Review",null,3,TaskStatus.OPEN,OffsetDateTime.now(),null);
    when(service.add("Review",null,3)).thenReturn(task);
    assertEquals("追加: 12 | Review",TaskCommand.reportAdd(service,"Review",null,3));
    when(service.listOpen(new TaskQuery(3,null))).thenReturn(List.of(task));
    assertEquals("12 | 3 |  | Review",TaskCommand.reportList(service,3,null));
    when(service.complete(12)).thenReturn(task);assertEquals("完了: 12 | Review",TaskCommand.reportDone(service,12));
    assertEquals("削除: 12",TaskCommand.reportDelete(service,12));verify(service).delete(12);
  }
}
