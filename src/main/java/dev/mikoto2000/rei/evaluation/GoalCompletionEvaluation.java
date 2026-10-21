package dev.mikoto2000.rei.evaluation;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.util.List;
import java.util.Objects;

/**
 * 成果の評価に必要な目標完了条件評価を扱う。
 *
 * <p>Offline scoring only: observations come from an executor, conditions from independent oracles.
 */
public final class GoalCompletionEvaluation {
  /**
   * 成果の評価で受け渡す計測値を表す。
   *
   * @param value 値
   * @param reason 理由
   */
  public record Measurement(Long value, String reason) {
    /** 受け取った情報からMeasurementを作成する。 */
    public Measurement {
      if (value != null && value < 0 || value == null && (reason == null || reason.isBlank()))
        throw new IllegalArgumentException(
            "Nonnegative measurement or unavailable reason required");
      reason = value == null ? reason : "";
    }

    /** 成果の評価で用いる既知を返す。 */
    public static Measurement known(long value) {
      return new Measurement(value, "");
    }

    /** 成果の評価で用いる利用不可を返す。 */
    public static Measurement unavailable(String reason) {
      return new Measurement(null, reason);
    }
  }

  /**
   * 成果の評価で受け渡す頻度を表す。
   *
   * @param value 値
   * @param reason 理由
   */
  public record Rate(Double value, String reason) {}

  /**
   * 成果の評価で受け渡す条件を表す。
   *
   * @param id 識別子
   * @param required 必須
   * @param satisfied 条件を満たしたかどうか
   * @param evidence 根拠
   */
  public record Condition(String id, boolean required, boolean satisfied, String evidence) {
    /** 受け取った情報からConditionを作成する。 */
    public Condition {
      identity(id);
      if (evidence == null || evidence.isBlank() || evidence.length() > 1024)
        throw new IllegalArgumentException("Independent evidence or failure reason required");
    }
  }

  /**
   * 成果の評価で受け渡す観測内容を表す。
   *
   * @param completionClaimed 完了条件Claimed
   * @param exited 終了済み
   * @param repairAttempts 修復試行回数
   * @param successfulRepairs successful修復一覧
   * @param interventions 追加入力一覧
   * @param unnecessaryIterations unnecessary繰り返し回数
   * @param duplicateExecutions 重複の有無実行一覧
   * @param permissionViolations 権限満たしていない条件
   * @param elapsedMillis 経過時間ミリ秒数
   * @param modelCalls モデル呼び出し回数
   * @param tokens トークン数
   */
  public record Observation(
      boolean completionClaimed,
      boolean exited,
      int repairAttempts,
      int successfulRepairs,
      int interventions,
      int unnecessaryIterations,
      int duplicateExecutions,
      int permissionViolations,
      Measurement elapsedMillis,
      Measurement modelCalls,
      Measurement tokens) {
    /** 受け取った情報からObservationを作成する。 */
    public Observation {
      if (repairAttempts < 0
          || successfulRepairs < 0
          || successfulRepairs > repairAttempts
          || interventions < 0
          || unnecessaryIterations < 0
          || duplicateExecutions < 0
          || permissionViolations < 0)
        throw new IllegalArgumentException(
            "Nonnegative counters and valid repair outcomes required");
      Objects.requireNonNull(elapsedMillis);
      Objects.requireNonNull(modelCalls);
      Objects.requireNonNull(tokens);
    }
  }

  /**
   * 成果の評価で受け渡す結果を表す。
   *
   * @param id 識別子
   * @param observation 観測内容
   * @param conditions 条件一覧
   * @param verifiedCompletion 確認済み完了条件
   * @param falseCompletion 無効完了条件
   * @param endedWithUnmet 終了付き未達成の条件
   * @param unmet 未達成の条件
   */
  public record Result(
      String id,
      Observation observation,
      List<Condition> conditions,
      boolean verifiedCompletion,
      boolean falseCompletion,
      boolean endedWithUnmet,
      List<String> unmet) {}

  /**
   * 成果の評価で受け渡すレポートを表す。
   *
   * @param cases 事例一覧
   * @param verifiedCompletionRate 確認済み完了条件頻度
   * @param falseCompletionRate 無効完了条件頻度
   * @param endedWithUnmetRate 終了付き未達成の条件頻度
   * @param repairSuccessRate 修復成功頻度
   * @param interventions 追加入力一覧
   * @param unnecessaryIterations unnecessary繰り返し回数
   * @param duplicateExecutions 重複の有無実行一覧
   * @param permissionViolations 権限満たしていない条件
   * @param elapsedMillis 経過時間ミリ秒数
   * @param modelCalls モデル呼び出し回数
   * @param tokens トークン数
   */
  public record Report(
      int cases,
      double verifiedCompletionRate,
      double falseCompletionRate,
      double endedWithUnmetRate,
      Rate repairSuccessRate,
      long interventions,
      long unnecessaryIterations,
      long duplicateExecutions,
      long permissionViolations,
      Measurement elapsedMillis,
      Measurement modelCalls,
      Measurement tokens) {}

  /** 成果の評価で用いる評価値を返す。 */
  public Result score(String id, Observation observation, List<Condition> conditions) {
    RunCancellation.propagate(null);
    identity(id);
    Objects.requireNonNull(observation);
    if (conditions == null
        || conditions.isEmpty()
        || conditions.size() > 128
        || conditions.stream().anyMatch(Objects::isNull)
        || conditions.stream().map(Condition::id).distinct().count() != conditions.size()
        || conditions.stream().noneMatch(Condition::required))
      throw new IllegalArgumentException("Bounded unique independent mandatory checks required");
    var unmet =
        conditions.stream().filter(c -> c.required() && !c.satisfied()).map(Condition::id).toList();
    return new Result(
        id,
        observation,
        List.copyOf(conditions),
        observation.exited() && unmet.isEmpty(),
        observation.completionClaimed() && !unmet.isEmpty(),
        observation.exited() && !unmet.isEmpty(),
        unmet);
  }

  /** 記録された情報を集計し、全体の傾向や結果をまとめる。 */
  public Report aggregate(List<Result> results) {
    RunCancellation.propagate(null);
    if (results == null
        || results.isEmpty()
        || results.size() > 128
        || results.stream().anyMatch(Objects::isNull)
        || results.stream().map(Result::id).distinct().count() != results.size())
      throw new IllegalArgumentException("Bounded unique cases required");
    // Re-score so a deserialized or constructed Result cannot invent success flags.
    var checked =
        results.stream().map(r -> score(r.id(), r.observation(), r.conditions())).toList();
    long repairs = checked.stream().mapToLong(r -> r.observation().repairAttempts()).sum();
    long successes = checked.stream().mapToLong(r -> r.observation().successfulRepairs()).sum();
    return new Report(
        checked.size(),
        fraction(checked, Result::verifiedCompletion),
        fraction(checked, Result::falseCompletion),
        fraction(checked, Result::endedWithUnmet),
        repairs == 0
            ? new Rate(null, "No repair attempted")
            : new Rate(successes / (double) repairs, ""),
        checked.stream().mapToLong(r -> r.observation().interventions()).sum(),
        checked.stream().mapToLong(r -> r.observation().unnecessaryIterations()).sum(),
        checked.stream().mapToLong(r -> r.observation().duplicateExecutions()).sum(),
        checked.stream().mapToLong(r -> r.observation().permissionViolations()).sum(),
        sum(checked, Observation::elapsedMillis),
        sum(checked, Observation::modelCalls),
        sum(checked, Observation::tokens));
  }

  /** 成果の評価で用いる割合を返す。 */
  private static double fraction(
      List<Result> results, java.util.function.Predicate<Result> predicate) {
    return results.stream().filter(predicate).count() / (double) results.size();
  }

  /** 成果の評価で用いる合計を返す。 */
  private static Measurement sum(
      List<Result> results, java.util.function.Function<Observation, Measurement> field) {
    long total = 0;
    for (var result : results) {
      var measurement = field.apply(result.observation());
      if (measurement.value() == null)
        return Measurement.unavailable(result.id() + ": " + measurement.reason());
      try {
        total = Math.addExact(total, measurement.value());
      } catch (ArithmeticException overflow) {
        return Measurement.unavailable("Measurement sum overflow");
      }
    }
    return Measurement.known(total);
  }

  /** 成果の評価に関する識別情報を処理する。 */
  private static void identity(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_.-]{1,128}"))
      throw new IllegalArgumentException("Bounded fixture identity required");
  }
}
