package dev.mikoto2000.rei.core.contextbudget;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;

/**
 * 限られた context budget の中で、現在のタスクに重要な情報を優先して LLM に渡す。
 *
 * <p>決定的な優先順位・件数制限・文字数/トークン概算で処理する。追加 LLM 呼び出しは行わない。</p>
 */
public class ContextBudgetManager {

  private static final Logger log = LoggerFactory.getLogger(ContextBudgetManager.class);

  /** トークン概算のための文字数係数。 */
  static final int CHARS_PER_TOKEN = 4;

  private final int modelContextLimit;
  private final int outputReserve;
  private final int safetyMargin;
  private final AgentEventFactory events;
  private final AgentEventPublisher eventPublisher;

  public ContextBudgetManager(int modelContextLimit, int outputReserve, int safetyMargin) {
    this(modelContextLimit, outputReserve, safetyMargin, null, null);
  }

  public ContextBudgetManager(int modelContextLimit, int outputReserve, int safetyMargin,
      AgentEventFactory events, AgentEventPublisher eventPublisher) {
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

  /** セクションのトークン概算を返す。 */
  public int estimateTokens(ContextSection section) {
    return Math.max(1, section.content().length() / CHARS_PER_TOKEN);
  }

  /** 優先順位に基づいてセクションを割り当てる。 */
  public AllocationResult allocate(List<ContextSection> sections) {
    List<ContextSection> sorted = new ArrayList<>(sections);
    sorted.sort((a, b) -> Integer.compare(priority(b.name()), priority(a.name())));

    List<String> included = new ArrayList<>();
    List<String> dropped = new ArrayList<>();
    int total = 0;
    int budget = inputBudget();
    for (ContextSection section : sorted) {
      int tokens = estimateTokens(section);
      if (isProtected(section.name())) {
        included.add(section.name());
        total += tokens;
        continue;
      }
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

  private void publishAllocation(int budget, int total, List<String> included, List<String> dropped) {
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
