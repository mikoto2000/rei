package dev.mikoto2000.rei.briefing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 日次案内ツール一覧の結果と状態の契約を検証するテスト。 */
class BriefingToolsTest {

  /** 日次情報の案内で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void dailyBriefingReturnsTodaysBriefing() throws Exception {
    BriefingService service = org.mockito.Mockito.mock(BriefingService.class);
    DailyBriefing expected =
        new DailyBriefing(
            LocalDate.of(2026, 3, 27),
            List.of(),
            List.of(),
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
