package dev.mikoto2000.rei.briefing.command;

import dev.mikoto2000.rei.briefing.BriefingService;
import dev.mikoto2000.rei.briefing.DailyBriefing;
import dev.mikoto2000.rei.feed.FeedBriefingItem;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary;
import dev.mikoto2000.rei.task.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(
    name = "briefing",
    description = "日次ブリーフィングを表示します",
    subcommands = {BriefingCommand.TodayCommand.class})
public class BriefingCommand {
  public static String reportToday(BriefingService service) {
    var command = new TodayCommand(service);
    var text = new java.io.StringWriter();
    command.out = new java.io.PrintWriter(text, true);
    command.run();
    return text.toString().replaceAll("[\\r\\n]+$", "");
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "today", description = "今日の日次ブリーフィングを表示します")
  public static class TodayCommand implements Runnable {

    private final BriefingService briefingService;
    private java.io.PrintWriter out = new java.io.PrintWriter(System.out, true);

    @Override
    public void run() {
      try {
        print(briefingService.today());
      } catch (Exception e) {
        throw new RuntimeException("日次ブリーフィングの生成に失敗しました", e);
      }
    }

    private void print(DailyBriefing briefing) {
      out.println("日次ブリーフィング | " + briefing.date());
      out.println("概要: " + briefing.overview());

      out.println("予定:");
      if (briefing.events().isEmpty()) {
        out.println("- 予定はありません");
      } else {
        for (GoogleCalendarEventSummary event : briefing.events()) {
          out.println("- " + event.start() + " | " + event.summary() + " | " + event.location());
        }
      }

      out.println("未完了タスク:");
      if (briefing.openTasks().isEmpty()) {
        out.println("- 未完了タスクはありません");
      } else {
        for (Task task : briefing.openTasks()) {
          String dueDate = task.dueDate() == null ? "" : task.dueDate().toString();
          out.println("- " + task.id() + " | " + dueDate + " | " + task.title());
        }
      }

      out.println("関連文書:");
      if (briefing.relatedDocuments().isEmpty()) {
        out.println("- 関連文書はありません");
      } else {
        for (String document : briefing.relatedDocuments()) {
          out.println("- " + document);
        }
      }

      out.println("新着記事:");
      if (briefing.feedItems().isEmpty()) {
        out.println("- 昨日 00:00 以降の新着記事はありませんでした");
      } else {
        for (FeedBriefingItem item : briefing.feedItems()) {
          out.println(
              "- "
                  + item.publishedAt()
                  + " | "
                  + item.feedName()
                  + " | "
                  + item.title()
                  + " | "
                  + item.url());
        }
      }

      out.println("気になりそうな話題:");
      if (briefing.interestUpdates().isEmpty()) {
        out.println("- 該当なし");
      } else {
        for (String update : briefing.interestUpdates()) {
          out.println("- " + update);
        }
      }

      out.println("注意点:");
      for (String cautionPoint : briefing.cautionPoints()) {
        out.println("- " + cautionPoint);
      }

      out.println("次アクション:");
      for (String nextAction : briefing.nextActions()) {
        out.println("- " + nextAction);
      }
    }
  }
}
