package dev.mikoto2000.rei.briefing;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BriefingTools {

  private final BriefingService briefingService;

  @Tool(name = "dailyBriefing", description = "今日の日次ブリーフィングを返します。予定、未完了タスク、関連文書、注意点、次アクションをまとめて確認できます。")
  DailyBriefing dailyBriefing() throws Exception {
    return briefingService.today();
  }
}
