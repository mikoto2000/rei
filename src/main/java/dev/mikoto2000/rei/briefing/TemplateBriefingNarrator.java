package dev.mikoto2000.rei.briefing;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class TemplateBriefingNarrator implements BriefingNarrator {

  @Override
  public BriefingNarration narrate(BriefingContext context) {
    String overview = buildOverview(context);
    List<String> cautionPoints = buildCautionPoints(context);
    List<String> nextActions = buildNextActions(context);
    return new BriefingNarration(overview, cautionPoints, nextActions);
  }

  private String buildOverview(BriefingContext context) {
    return String.format("%s の予定は %d 件、未完了タスクは %d 件です。",
        context.date(), context.events().size(), context.openTasks().size());
  }

  private List<String> buildCautionPoints(BriefingContext context) {
    List<String> cautionPoints = new ArrayList<>();
    if (!context.overdueTasks().isEmpty()) {
      cautionPoints.add("期限切れタスクが " + context.overdueTasks().size() + " 件あります。");
    }
    if (context.events().isEmpty()) {
      cautionPoints.add("今日の予定は登録されていません。");
    }
    return cautionPoints;
  }

  private List<String> buildNextActions(BriefingContext context) {
    List<String> nextActions = new ArrayList<>();
    if (!context.events().isEmpty()) {
      nextActions.add("最初の予定に向けて必要資料を確認する。");
    }
    if (!context.openTasks().isEmpty()) {
      nextActions.add("優先度の高い未完了タスクから着手する。");
    }
    if (nextActions.isEmpty()) {
      nextActions.add("新しく着手するタスクを整理する。");
    }
    return nextActions;
  }
}
