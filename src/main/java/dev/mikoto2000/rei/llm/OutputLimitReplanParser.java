package dev.mikoto2000.rei.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** 出力上限計画の見直しの入力を解釈し、機能内で扱える情報へ整える。 */
public class OutputLimitReplanParser {

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper objectMapper;

  /** 用途に応じたモデル利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public OutputLimitReplanParser() {
    this(new ObjectMapper());
  }

  /** 指定された値や利用先を使って、OutputLimitReplanParserを初期化する。 */
  OutputLimitReplanParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public OutputLimitReplanPlan parse(String plannerOutput, int maxSubgoals) {
    try {
      PlannerPlan plan =
          objectMapper.readValue(normalizeJsonObject(plannerOutput), PlannerPlan.class);
      return validate(plan, maxSubgoals);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to parse output limit replan", e);
    }
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  private OutputLimitReplanPlan validate(PlannerPlan plan, int maxSubgoals) {
    if (plan == null || plan.subgoals == null || plan.subgoals.isEmpty()) {
      throw new IllegalArgumentException("Planner subgoals must not be empty");
    }
    int limit = Math.max(1, maxSubgoals);
    List<OutputLimitReplanSubgoal> subgoals = new ArrayList<>();
    for (PlannerSubgoal subgoal : plan.subgoals) {
      if (subgoals.size() >= limit) {
        break;
      }
      String goal = trimToNull(subgoal == null ? null : subgoal.goal);
      if (goal == null) {
        throw new IllegalArgumentException("Planner subgoal goal must not be blank");
      }
      String id = trimToNull(subgoal.id);
      subgoals.add(
          new OutputLimitReplanSubgoal(id == null ? "subgoal-" + (subgoals.size() + 1) : id, goal));
    }
    if (subgoals.isEmpty()) {
      throw new IllegalArgumentException("Planner subgoals must not be empty");
    }
    String finalGoal = trimToNull(plan.finalGoal);
    return new OutputLimitReplanPlan(subgoals, finalGoal == null ? "サブゴールの結果を統合する" : finalGoal);
  }

  /** 同じ意味の指定を共通に扱えるよう、JSON変換対象データの表記をそろえる。 */
  static String normalizeJsonObject(String response) {
    if (response == null || response.isBlank()) {
      return "{}";
    }
    String trimmed = response.trim();
    if (trimmed.startsWith("```")) {
      int firstNewline = trimmed.indexOf('\n');
      if (firstNewline >= 0) {
        trimmed = trimmed.substring(firstNewline + 1).trim();
      }
      int closingFence = trimmed.lastIndexOf("```");
      if (closingFence >= 0) {
        trimmed = trimmed.substring(0, closingFence).trim();
      }
    }
    int objectStart = trimmed.indexOf('{');
    int objectEnd = trimmed.lastIndexOf('}');
    if (objectStart >= 0 && objectEnd >= objectStart) {
      return trimmed.substring(objectStart, objectEnd + 1).trim();
    }
    return trimmed;
  }

  /** 用途に応じたモデル利用で用いる余白の除去終点未指定を返す。 */
  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** 用途に応じたモデル利用に必要な計画の作成計画を扱う。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class PlannerPlan {
    /** 小さな目標。 */
    public List<PlannerSubgoal> subgoals;

    /** 最終目標。 */
    public String finalGoal;
  }

  /** 用途に応じたモデル利用に必要な計画の作成Subgoalを扱う。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class PlannerSubgoal {
    /** 識別子。 */
    public String id;

    /** 目標。 */
    public String goal;
  }
}
