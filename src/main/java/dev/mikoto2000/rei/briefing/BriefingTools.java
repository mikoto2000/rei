package dev.mikoto2000.rei.briefing;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** 日次案内をモデルから呼び出せるツールとして提供する。 */
@Component
@RequiredArgsConstructor
public class BriefingTools {

  /** 日次案内サービス。 */
  private final BriefingService briefingService;

  /** 日次情報の案内で用いる日次日次案内を返す。 */
  @Tool(
      name = "dailyBriefing",
      description = "今日の日次ブリーフィングを返します。予定、未完了タスク、関連文書、注意点、次アクションをまとめて確認できます。")
  DailyBriefing dailyBriefing() throws Exception {
    IO.println("今日の日次ブリーフィングを取得するよ");
    return briefingService.today();
  }
}
