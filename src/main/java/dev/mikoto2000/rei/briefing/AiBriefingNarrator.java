package dev.mikoto2000.rei.briefing;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AiBriefingNarrator implements BriefingNarrator {

  private final ChatModel chatModel;
  private final ModelHolderService modelHolderService;

  @Override
  public BriefingNarration narrate(BriefingContext context) {
    Prompt prompt = new Prompt(
        buildPrompt(context),
        OpenAiChatOptions.builder()
            .model(modelHolderService.get())
            .build());

    String response = chatModel.call(prompt).getResult().getOutput().getText();
    return parse(response);
  }

  private String buildPrompt(BriefingContext context) {
    return """
        あなたは AI 秘書です。与えられた情報から、今日の業務開始時に読む日次ブリーフィングを日本語で作成してください。
        次の形式を厳守してください。
        OVERVIEW: 1段落
        CAUTIONS:
        - 箇条書き
        NEXT_ACTIONS:
        - 箇条書き

        対象日: %s

        予定:
        %s

        未完了タスク:
        %s

        期限切れタスク:
        %s

        関連文書:
        %s
        """.formatted(
            context.date(),
            formatEvents(context.events()),
            formatTasks(context.openTasks()),
            formatTasks(context.overdueTasks()),
            formatDocuments(context.relatedDocuments()));
  }

  private String formatEvents(List<dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary> events) {
    if (events.isEmpty()) {
      return "- なし";
    }
    return events.stream()
        .map(event -> "- " + event.start() + " | " + event.summary() + " | " + event.location())
        .reduce((left, right) -> left + "\n" + right)
        .orElse("- なし");
  }

  private String formatTasks(List<dev.mikoto2000.rei.task.Task> tasks) {
    if (tasks.isEmpty()) {
      return "- なし";
    }
    return tasks.stream()
        .map(task -> "- " + task.id() + " | " + (task.dueDate() == null ? "" : task.dueDate()) + " | " + task.title())
        .reduce((left, right) -> left + "\n" + right)
        .orElse("- なし");
  }

  private String formatDocuments(List<String> relatedDocuments) {
    if (relatedDocuments.isEmpty()) {
      return "- なし";
    }
    return relatedDocuments.stream()
        .map(document -> "- " + document)
        .reduce((left, right) -> left + "\n" + right)
        .orElse("- なし");
  }

  private BriefingNarration parse(String response) {
    String overview = "";
    List<String> cautionPoints = new ArrayList<>();
    List<String> nextActions = new ArrayList<>();
    Section section = Section.NONE;

    for (String rawLine : response.split("\\R")) {
      String line = rawLine.trim();
      if (line.isBlank()) {
        continue;
      }
      if (line.startsWith("OVERVIEW:")) {
        overview = line.substring("OVERVIEW:".length()).trim();
        section = Section.OVERVIEW;
        continue;
      }
      if (line.equals("CAUTIONS:")) {
        section = Section.CAUTIONS;
        continue;
      }
      if (line.equals("NEXT_ACTIONS:")) {
        section = Section.NEXT_ACTIONS;
        continue;
      }
      if (line.startsWith("- ")) {
        if (section == Section.CAUTIONS) {
          cautionPoints.add(line.substring(2).trim());
        } else if (section == Section.NEXT_ACTIONS) {
          nextActions.add(line.substring(2).trim());
        }
        continue;
      }
      if (section == Section.OVERVIEW) {
        overview = overview.isBlank() ? line : overview + " " + line;
      }
    }

    if (overview.isBlank()) {
      return TemplateBriefingNarrator.fallback(response);
    }
    return new BriefingNarration(overview, cautionPoints, nextActions);
  }

  private enum Section {
    NONE,
    OVERVIEW,
    CAUTIONS,
    NEXT_ACTIONS
  }
}
