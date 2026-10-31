package dev.mikoto2000.rei.core.contextbudget;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 限られた context budget の中で、現在のタスクに重要な情報を優先して LLM に渡す。
 *
 * <p>決定的な優先順位・件数制限・文字数/トークン概算で処理する。追加 LLM 呼び出しは行わない。
 */
public class ContextBudgetManager {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(ContextBudgetManager.class);

  /** トークン概算のための文字数係数。 */
  static final int CHARS_PER_TOKEN = 4;

  /** モデルが受け付ける文脈のトークン数上限。 */
  private final int modelContextLimit;

  /** 回答を生成するために入力から差し引いて確保するトークン数。 */
  private final int outputReserve;

  /** トークン数の概算誤差に備えて確保する余裕。 */
  private final int safetyMargin;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** モデルへ渡す文脈の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ContextBudgetManager(int modelContextLimit, int outputReserve, int safetyMargin) {
    this(modelContextLimit, outputReserve, safetyMargin, null, null);
  }

  /** 指定された値や利用先を使って、ContextBudgetManagerを初期化する。 */
  public ContextBudgetManager(
      int modelContextLimit,
      int outputReserve,
      int safetyMargin,
      AgentEventFactory events,
      AgentEventPublisher eventPublisher) {
    this.modelContextLimit = modelContextLimit;
    this.outputReserve = outputReserve;
    this.safetyMargin = safetyMargin;
    this.events = events;
    this.eventPublisher = eventPublisher;
  }

  /** 入力用 budget を計算する。 */
  public int inputBudget() {
    return modelContextLimit - outputReserve - safetyMargin;
  }

  /** 最終送信と取得計画が共有する、回答・schema予約後の入力上限。 */
  public static int inputLimit(
      ContextCompressionProperties properties,
      TokenEstimator estimator,
      org.springframework.ai.chat.prompt.Prompt prompt) {
    int completion = properties.getCompletionReserve();
    if (prompt.getOptions() != null && prompt.getOptions().getMaxTokens() != null)
      completion = Math.max(completion, prompt.getOptions().getMaxTokens());
    if (prompt.getOptions() instanceof org.springframework.ai.openai.OpenAiChatOptions options
        && options.getMaxCompletionTokens() != null)
      completion = Math.max(completion, options.getMaxCompletionTokens());
    int schema = 0;
    if (prompt.getOptions()
            instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
        && options.getToolCallbacks() != null)
      for (var callback : options.getToolCallbacks())
        schema +=
            estimator.text(callback.getToolDefinition().inputSchema())
                + estimator.text(callback.getToolDefinition().description())
                + 16;
    return Math.min(
        properties.getHardLimit(),
        new ContextBudgetManager(
                properties.contextLimit(
                    prompt.getOptions() == null ? null : prompt.getOptions().getModel()),
                completion,
                properties.getSafetyMargin() + Math.max(properties.getToolReserve(), schema))
            .inputBudget());
  }

  /** 利用量や必要量を判断するため、メッセージ一覧を概算する。 */
  public static long estimateMessages(
      java.util.List<org.springframework.ai.chat.messages.Message> messages,
      TokenEstimator estimator) {
    return messages.stream().mapToLong(estimator::message).sum();
  }

  /** セクションのトークン概算を返す。 */
  public int estimateTokens(ContextSection section) {
    return Math.max(1, section.content().length() / CHARS_PER_TOKEN);
  }

  /** 優先順位に基づいてセクションを割り当てる。 */
  public AllocationResult allocate(List<ContextSection> sections) {
    List<ContextSection> sorted = new ArrayList<>(sections);
    // 予算が不足しても現在の依頼に必要な情報を残せるよう、重要な文脈から採用する。
    sorted.sort((a, b) -> Integer.compare(priority(a.name()), priority(b.name())));

    List<String> included = new ArrayList<>();
    List<String> dropped = new ArrayList<>();
    int total = 0;
    int budget = inputBudget();
    for (ContextSection section : sorted) {
      int tokens = estimateTokens(section);
      // システム指示と今回の依頼は、ほかの資料を省略する場合でも残す。
      if (isProtected(section.name())) {
        included.add(section.name());
        total += tokens;
        continue;
      }
      // 残りの資料は入力予算内で採用し、渡せなかった情報を後で確認できるよう記録する。
      if (total + tokens <= budget) {
        included.add(section.name());
        total += tokens;
      } else {
        dropped.add(section.name());
      }
    }
    log.debug("Context budget: {} estimated tokens", total);
    log.debug("Included: {}", included);
    log.debug("Dropped: {}", dropped);
    publishAllocation(budget, total, included, dropped);
    return new AllocationResult(List.copyOf(included), List.copyOf(dropped), total);
  }

  /** 文脈の採用・除外の結果を通知し、どの情報がモデルへ渡ったか確認できるようにする。 */
  private void publishAllocation(
      int budget, int total, List<String> included, List<String> dropped) {
    if (events == null || eventPublisher == null) {
      return;
    }
    eventPublisher.publish(events.contextBudgetEvaluated(budget, total, included, dropped));
    if (!dropped.isEmpty()) {
      eventPublisher.publish(events.contextBudgetTrimmed(budget, total, dropped));
    }
  }

  /** 絶対に削ってはいけないセクション。 */
  private boolean isProtected(String name) {
    return "SYSTEM".equals(name) || "CURRENT_USER".equals(name);
  }

  /** 優先順位（小さいほど高い）。 */
  private int priority(String name) {
    return switch (name) {
      case "SYSTEM" -> 1;
      case "CURRENT_USER" -> 2;
      case "TASK_STATE" -> 3;
      case "ACTION_PLAN" -> 4;
      case "REPLAN_NOTICE" -> 5;
      case "CHECKPOINT" -> 6;
      case "WORKING_SET" -> 7;
      case "RECENT_CHANGES" -> 8;
      case "FILE_SUMMARIES" -> 9;
      case "RELATED_FILES" -> 10;
      case "CONVERSATION_HISTORY" -> 11;
      case "TOOL_RESULTS" -> 12;
      default -> 100;
    };
  }
}
