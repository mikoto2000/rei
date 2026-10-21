package dev.mikoto2000.rei.briefing;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 日次情報の案内に必要なAI日次案内読み上げの案内を扱う。 */
@Component
public class AiBriefingNarrator implements BriefingNarrator {

  /** モデル提供元。 */
  private final LlmModelProvider modelProvider;

  /** モデル保持先サービス。 */
  private final ModelHolderService modelHolderService;

  /** 日次情報の案内を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AiBriefingNarrator(ChatModel chatModel, ModelHolderService modelHolderService) {
    this(new FixedLlmModelProvider(chatModel), modelHolderService);
  }

  /** 指定された値や利用先を使って、AiBriefingNarratorを初期化する。 */
  @Autowired
  public AiBriefingNarrator(LlmModelProvider modelProvider, ModelHolderService modelHolderService) {
    this.modelProvider = modelProvider;
    this.modelHolderService = modelHolderService;
  }

  /** 日次情報の案内で用いるnarrateを返す。 */
  @Override
  public BriefingNarration narrate(BriefingContext context) {
    Prompt prompt =
        new Prompt(
            buildPrompt(context),
            modelProvider.chatOptions(LlmFeature.BRIEFING, modelHolderService.get()));

    String response =
        modelProvider.chatModel(LlmFeature.BRIEFING).call(prompt).getResult().getOutput().getText();
    return parse(response);
  }

  /** 利用先に渡せるよう、プロンプトを組み立てる。 */
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
        """
        .formatted(
            context.date(),
            formatEvents(context.events()),
            formatTasks(context.openTasks()),
            formatTasks(context.overdueTasks()),
            formatDocuments(context.relatedDocuments()));
  }

  /** 内容を表示・参照しやすくするため、イベント一覧を整形する。 */
  private String formatEvents(
      List<dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary> events) {
    if (events.isEmpty()) {
      return "- なし";
    }
    return events.stream()
        .map(event -> "- " + event.start() + " | " + event.summary() + " | " + event.location())
        .reduce((left, right) -> left + "\n" + right)
        .orElse("- なし");
  }

  /** 内容を表示・参照しやすくするため、タスク一覧を整形する。 */
  private String formatTasks(List<dev.mikoto2000.rei.task.Task> tasks) {
    if (tasks.isEmpty()) {
      return "- なし";
    }
    return tasks.stream()
        .map(
            task ->
                "- "
                    + task.id()
                    + " | "
                    + (task.dueDate() == null ? "" : task.dueDate())
                    + " | "
                    + task.title())
        .reduce((left, right) -> left + "\n" + right)
        .orElse("- なし");
  }

  /** 内容を表示・参照しやすくするため、保存済み文書を整形する。 */
  private String formatDocuments(List<String> relatedDocuments) {
    if (relatedDocuments.isEmpty()) {
      return "- なし";
    }
    return relatedDocuments.stream()
        .map(document -> "- " + document)
        .reduce((left, right) -> left + "\n" + right)
        .orElse("- なし");
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
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

  /** 日次情報の案内における節の選択肢。 */
  private enum Section {
    /** 該当なし。 */
    NONE,
    /** 概要。 */
    OVERVIEW,
    /** 注意が必要な情報の区分。 */
    CAUTIONS,
    /** 次操作一覧。 */
    NEXT_ACTIONS
  }

  /** 固定言語モデルモデルを用途や設定に応じて提供する窓口。 */
  private static class FixedLlmModelProvider extends LlmModelProvider {
    /** 応答生成に使うモデル。 */
    private final ChatModel chatModel;

    /** 指定された値や利用先を使って、FixedLlmModelProviderを初期化する。 */
    FixedLlmModelProvider(ChatModel chatModel) {
      super(chatModel, new dev.mikoto2000.rei.llm.LlmProperties());
      this.chatModel = chatModel;
    }

    /** 日次情報の案内で用いる応答生成に使うモデルを返す。 */
    @Override
    public ChatModel chatModel(String feature) {
      return chatModel;
    }

    /** 日次情報の案内で用いるモデルを返す。 */
    @Override
    public String model(String feature, String defaultModel) {
      return defaultModel;
    }
  }
}
