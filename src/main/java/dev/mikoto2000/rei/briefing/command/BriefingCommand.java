package dev.mikoto2000.rei.briefing.command;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.briefing.BriefingService;
import dev.mikoto2000.rei.briefing.DailyBriefing;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary;
import dev.mikoto2000.rei.task.Task;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;

@Component
@Command(
    name = "briefing",
    description = "日次ブリーフィングを表示します",
    subcommands = {
      BriefingCommand.TodayCommand.class
    })
public class BriefingCommand {

  @Component
  @RequiredArgsConstructor
  @Command(name = "today", description = "今日の日次ブリーフィングを表示します")
  public static class TodayCommand implements Runnable {

    private final BriefingService briefingService;

    @Override
    public void run() {
      try {
        print(briefingService.today());
      } catch (Exception e) {
        throw new RuntimeException("日次ブリーフィングの生成に失敗しました", e);
      }
    }

    private void print(DailyBriefing briefing) {
      System.out.println("日次ブリーフィング | " + briefing.date());
      System.out.println("概要: " + briefing.overview());

      System.out.println("予定:");
      if (briefing.events().isEmpty()) {
        System.out.println("- 予定はありません");
      } else {
        for (GoogleCalendarEventSummary event : briefing.events()) {
          System.out.println("- " + event.start() + " | " + event.summary() + " | " + event.location());
        }
      }

      System.out.println("未完了タスク:");
      if (briefing.openTasks().isEmpty()) {
        System.out.println("- 未完了タスクはありません");
      } else {
        for (Task task : briefing.openTasks()) {
          String dueDate = task.dueDate() == null ? "" : task.dueDate().toString();
          System.out.println("- " + task.id() + " | " + dueDate + " | " + task.title());
        }
      }

      System.out.println("関連文書:");
      if (briefing.relatedDocuments().isEmpty()) {
        System.out.println("- 関連文書はありません");
      } else {
        for (String document : briefing.relatedDocuments()) {
          System.out.println("- " + document);
        }
      }

      System.out.println("注意点:");
      for (String cautionPoint : briefing.cautionPoints()) {
        System.out.println("- " + cautionPoint);
      }

      System.out.println("次アクション:");
      for (String nextAction : briefing.nextActions()) {
        System.out.println("- " + nextAction);
      }
    }
  }
}
