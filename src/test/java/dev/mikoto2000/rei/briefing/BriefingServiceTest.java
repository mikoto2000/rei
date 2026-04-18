package dev.mikoto2000.rei.briefing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary;
import dev.mikoto2000.rei.interest.InterestUpdate;
import dev.mikoto2000.rei.interest.InterestUpdateService;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import dev.mikoto2000.rei.task.Task;
import dev.mikoto2000.rei.task.TaskService;
import dev.mikoto2000.rei.task.TaskStatus;

class BriefingServiceTest {

  @Test
  void briefingForAggregatesEventsTasksAndDocuments() throws Exception {
    GoogleCalendarService calendarService = org.mockito.Mockito.mock(GoogleCalendarService.class);
    TaskService taskService = org.mockito.Mockito.mock(TaskService.class);
    VectorStore vectorStore = org.mockito.Mockito.mock(VectorStore.class);
    BriefingNarrator briefingNarrator = org.mockito.Mockito.mock(BriefingNarrator.class);
    InterestUpdateService interestUpdateService = org.mockito.Mockito.mock(InterestUpdateService.class);
    BriefingService service = new BriefingService(calendarService, taskService, vectorStore, briefingNarrator, interestUpdateService);

    LocalDate date = LocalDate.of(2026, 3, 27);
    GoogleCalendarEventSummary event = new GoogleCalendarEventSummary(
        "evt-1",
        "顧客定例",
        "2026-03-27T09:00:00+09:00",
        "2026-03-27T10:00:00+09:00",
        "会議室A",
        "confirmed");
    Task overdueTask = new Task(
        1L,
        "提案書更新",
        LocalDate.of(2026, 3, 26),
        1,
        TaskStatus.OPEN,
        List.of("sales"),
        OffsetDateTime.of(2026, 3, 20, 0, 0, 0, 0, ZoneOffset.UTC),
        null);
    Task todayTask = new Task(
        2L,
        "議事メモ整理",
        LocalDate.of(2026, 3, 27),
        2,
        TaskStatus.OPEN,
        List.of("meeting"),
        OffsetDateTime.of(2026, 3, 21, 0, 0, 0, 0, ZoneOffset.UTC),
        null);

    when(calendarService.listEventsForDate(date)).thenReturn(List.of(event));
    when(taskService.listOpen()).thenReturn(List.of(overdueTask, todayTask));
    when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(
        new Document("顧客向け提案資料の要点", java.util.Map.of("source", "docs/proposal.md"))));
    when(interestUpdateService.listRecent(24)).thenReturn(List.of(
        new InterestUpdate(
            1L,
            "Neovim 開発環境",
            "繰り返し話題になっている",
            "Neovim devcontainer best practices",
            "Neovim docs",
            List.of("https://example.com/nvim"),
            OffsetDateTime.of(2026, 3, 27, 0, 0, 0, 0, ZoneOffset.UTC))));
    when(briefingNarrator.narrate(any())).thenReturn(new BriefingNarration(
        "午前は顧客定例、午後は提案書更新のフォローが中心です。",
        List.of("期限切れタスクが 1 件あります。"),
        List.of("顧客定例の前に提案資料を確認する。")));

    DailyBriefing briefing = service.briefingFor(date);

    assertEquals(date, briefing.date());
    assertEquals(List.of(event), briefing.events());
    assertEquals(List.of(overdueTask, todayTask), briefing.openTasks());
    assertEquals(List.of(overdueTask), briefing.overdueTasks());
    assertEquals(List.of("docs/proposal.md | 顧客向け提案資料の要点"), briefing.relatedDocuments());
    assertEquals(List.of("Neovim 開発環境 | Neovim docs | https://example.com/nvim"), briefing.interestUpdates());
    assertEquals("午前は顧客定例、午後は提案書更新のフォローが中心です。", briefing.overview());
    assertEquals(List.of("期限切れタスクが 1 件あります。"), briefing.cautionPoints());
    assertEquals(List.of("顧客定例の前に提案資料を確認する。"), briefing.nextActions());

    ArgumentCaptor<BriefingContext> contextCaptor = ArgumentCaptor.forClass(BriefingContext.class);
    verify(briefingNarrator).narrate(contextCaptor.capture());
    assertEquals(List.of(event), contextCaptor.getValue().events());
    assertEquals(List.of(overdueTask), contextCaptor.getValue().overdueTasks());
  }

  @Test
  void briefingForUsesFallbackWhenNoEventsAndNoTasks() throws Exception {
    GoogleCalendarService calendarService = org.mockito.Mockito.mock(GoogleCalendarService.class);
    TaskService taskService = org.mockito.Mockito.mock(TaskService.class);
    VectorStore vectorStore = org.mockito.Mockito.mock(VectorStore.class);
    BriefingNarrator briefingNarrator = org.mockito.Mockito.mock(BriefingNarrator.class);
    InterestUpdateService interestUpdateService = org.mockito.Mockito.mock(InterestUpdateService.class);
    BriefingService service = new BriefingService(calendarService, taskService, vectorStore, briefingNarrator, interestUpdateService);

    LocalDate date = LocalDate.of(2026, 3, 27);
    when(calendarService.listEventsForDate(date)).thenReturn(List.of());
    when(taskService.listOpen()).thenReturn(List.of());
    when(interestUpdateService.listRecent(24)).thenReturn(List.of());

    DailyBriefing briefing = service.briefingFor(date);

    assertTrue(briefing.overview().contains("予定も未完了タスクもありません"));
    assertEquals(List.of(), briefing.events());
    assertEquals(List.of(), briefing.openTasks());
    assertEquals(List.of(), briefing.overdueTasks());
    assertEquals(List.of(), briefing.interestUpdates());
    verify(briefingNarrator, never()).narrate(any());
  }
}
