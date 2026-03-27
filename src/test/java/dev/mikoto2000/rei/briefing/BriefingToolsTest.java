package dev.mikoto2000.rei.briefing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class BriefingToolsTest {

  @Test
  void dailyBriefingReturnsTodaysBriefing() throws Exception {
    BriefingService service = org.mockito.Mockito.mock(BriefingService.class);
    DailyBriefing expected = new DailyBriefing(
        LocalDate.of(2026, 3, 27),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "今日は予定も未完了タスクもありません。必要なら先回りで準備や整理を進められます。",
        List.of("急ぎの対応は見当たりません。"),
        List.of("新しく着手するタスクや確認事項を整理する。"));
    BriefingTools tools = new BriefingTools(service);
    when(service.today()).thenReturn(expected);

    DailyBriefing actual = tools.dailyBriefing();

    assertEquals(expected, actual);
  }
}
