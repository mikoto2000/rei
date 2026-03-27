package dev.mikoto2000.rei.briefing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary;
import dev.mikoto2000.rei.task.Task;
import dev.mikoto2000.rei.task.TaskStatus;

class AiBriefingNarratorTest {

  @Test
  void narrateBuildsPromptAndParsesSections() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    ModelHolderService modelHolderService = org.mockito.Mockito.mock(ModelHolderService.class);
    AiBriefingNarrator narrator = new AiBriefingNarrator(chatModel, modelHolderService);
    when(modelHolderService.get()).thenReturn("gpt-4.1-mini");
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
        new Generation(new AssistantMessage("""
            OVERVIEW: 午前は顧客定例、午後は提案書更新のフォローが中心です。
            CAUTIONS:
            - 期限切れタスクが 1 件あります。
            - 会議前に提案資料を確認してください。
            NEXT_ACTIONS:
            - 顧客定例の論点を3つに絞る。
            - 提案書更新の期限を見直す。
            """)))));

    BriefingNarration narration = narrator.narrate(new BriefingContext(
        LocalDate.of(2026, 3, 27),
        List.of(new GoogleCalendarEventSummary(
            "evt-1",
            "顧客定例",
            "2026-03-27T09:00:00+09:00",
            "2026-03-27T10:00:00+09:00",
            "会議室A",
            "confirmed")),
        List.of(new Task(
            1L,
            "提案書更新",
            LocalDate.of(2026, 3, 26),
            1,
            TaskStatus.OPEN,
            List.of("sales"),
            OffsetDateTime.of(2026, 3, 20, 0, 0, 0, 0, ZoneOffset.UTC),
            null)),
        List.of(new Task(
            1L,
            "提案書更新",
            LocalDate.of(2026, 3, 26),
            1,
            TaskStatus.OPEN,
            List.of("sales"),
            OffsetDateTime.of(2026, 3, 20, 0, 0, 0, 0, ZoneOffset.UTC),
            null)),
        List.of("docs/proposal.md | 顧客向け提案資料の要点")));

    assertEquals("午前は顧客定例、午後は提案書更新のフォローが中心です。", narration.overview());
    assertEquals(List.of("期限切れタスクが 1 件あります。", "会議前に提案資料を確認してください。"), narration.cautionPoints());
    assertEquals(List.of("顧客定例の論点を3つに絞る。", "提案書更新の期限を見直す。"), narration.nextActions());

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    assertTrue(promptCaptor.getValue().getContents().contains("顧客定例"));
    assertTrue(promptCaptor.getValue().getContents().contains("docs/proposal.md"));
  }
}
