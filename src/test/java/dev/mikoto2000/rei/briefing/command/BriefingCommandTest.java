package dev.mikoto2000.rei.briefing.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.briefing.DailyBriefing;
import dev.mikoto2000.rei.briefing.BriefingService;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary;
import dev.mikoto2000.rei.task.Task;
import dev.mikoto2000.rei.task.TaskStatus;
import picocli.CommandLine;

class BriefingCommandTest {

  @Test
  void todayCommandPrintsDailyBriefing() throws Exception {
    BriefingService service = org.mockito.Mockito.mock(BriefingService.class);
    when(service.today()).thenReturn(new DailyBriefing(
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
            LocalDate.of(2026, 3, 27),
            1,
            TaskStatus.OPEN,
            List.of("sales"),
            OffsetDateTime.of(2026, 3, 20, 0, 0, 0, 0, ZoneOffset.UTC),
            null)),
        List.of(),
        List.of("docs/proposal.md | 顧客向け提案資料の要点"),
        List.of("Neovim 開発環境 | Neovim docs | https://example.com/nvim"),
        "午前は顧客定例、午後は提案書更新のフォローが中心です。",
        List.of("会議前に提案資料を確認してください。"),
        List.of("顧客定例の前に論点を3つに絞る。")));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service).execute("today");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    String output = out.toString();
    assertTrue(output.contains("日次ブリーフィング"));
    assertTrue(output.contains("顧客定例"));
    assertTrue(output.contains("提案書更新"));
    assertTrue(output.contains("docs/proposal.md"));
    assertTrue(output.contains("Neovim 開発環境"));
    assertTrue(output.contains("会議前に提案資料を確認してください。"));
  }

  private CommandLine newCommand(BriefingService service) {
    return new CommandLine(new BriefingCommand(), new CommandLine.IFactory() {
      @Override
      public <K> K create(Class<K> cls) throws Exception {
        if (cls == BriefingCommand.TodayCommand.class) {
          return cls.cast(new BriefingCommand.TodayCommand(service));
        }
        return CommandLine.defaultFactory().create(cls);
      }
    });
  }
}
