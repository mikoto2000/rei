package dev.mikoto2000.rei.evaluation;

import java.util.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;

/** Offline scoring only: observations come from an executor, conditions from independent oracles. */
public final class GoalCompletionEvaluation {
  public record Measurement(Long value, String reason) {
    public Measurement {
      if (value != null && value < 0 || value == null && (reason == null || reason.isBlank()))
        throw new IllegalArgumentException("Nonnegative measurement or unavailable reason required");
      reason = value == null ? reason : "";
    }
    public static Measurement known(long value) { return new Measurement(value, ""); }
    public static Measurement unavailable(String reason) { return new Measurement(null, reason); }
  }
  public record Rate(Double value, String reason) {}
  public record Condition(String id, boolean required, boolean satisfied, String evidence) {
    public Condition {
      identity(id);
      if (evidence == null || evidence.isBlank() || evidence.length() > 1024)
        throw new IllegalArgumentException("Independent evidence or failure reason required");
    }
  }
  public record Observation(boolean completionClaimed, boolean exited, int repairAttempts,
      int successfulRepairs, int interventions, int unnecessaryIterations, int duplicateExecutions,
      int permissionViolations, Measurement elapsedMillis, Measurement modelCalls, Measurement tokens) {
    public Observation {
      if (repairAttempts < 0 || successfulRepairs < 0 || successfulRepairs > repairAttempts
          || interventions < 0 || unnecessaryIterations < 0 || duplicateExecutions < 0 || permissionViolations < 0)
        throw new IllegalArgumentException("Nonnegative counters and valid repair outcomes required");
      Objects.requireNonNull(elapsedMillis); Objects.requireNonNull(modelCalls); Objects.requireNonNull(tokens);
    }
  }
  public record Result(String id, Observation observation, List<Condition> conditions,
      boolean verifiedCompletion, boolean falseCompletion, boolean endedWithUnmet, List<String> unmet) {}
  public record Report(int cases, double verifiedCompletionRate, double falseCompletionRate,
      double endedWithUnmetRate, Rate repairSuccessRate, long interventions, long unnecessaryIterations,
      long duplicateExecutions, long permissionViolations, Measurement elapsedMillis,
      Measurement modelCalls, Measurement tokens) {}

  public Result score(String id, Observation observation, List<Condition> conditions) {
    RunCancellation.propagate(null); identity(id); Objects.requireNonNull(observation);
    if (conditions == null || conditions.isEmpty() || conditions.size() > 128
        || conditions.stream().anyMatch(Objects::isNull)
        || conditions.stream().map(Condition::id).distinct().count() != conditions.size()
        || conditions.stream().noneMatch(Condition::required))
      throw new IllegalArgumentException("Bounded unique independent mandatory checks required");
    var unmet = conditions.stream().filter(c -> c.required() && !c.satisfied()).map(Condition::id).toList();
    return new Result(id, observation, List.copyOf(conditions), observation.exited() && unmet.isEmpty(),
        observation.completionClaimed() && !unmet.isEmpty(), observation.exited() && !unmet.isEmpty(), unmet);
  }
  public Report aggregate(List<Result> results) {
    RunCancellation.propagate(null);
    if (results == null || results.isEmpty() || results.size() > 128 || results.stream().anyMatch(Objects::isNull)
        || results.stream().map(Result::id).distinct().count() != results.size())
      throw new IllegalArgumentException("Bounded unique cases required");
    // Re-score so a deserialized or constructed Result cannot invent success flags.
    var checked = results.stream().map(r -> score(r.id(), r.observation(), r.conditions())).toList();
    long repairs = checked.stream().mapToLong(r -> r.observation().repairAttempts()).sum();
    long successes = checked.stream().mapToLong(r -> r.observation().successfulRepairs()).sum();
    return new Report(checked.size(), fraction(checked, Result::verifiedCompletion),
        fraction(checked, Result::falseCompletion), fraction(checked, Result::endedWithUnmet),
        repairs == 0 ? new Rate(null, "No repair attempted") : new Rate(successes / (double) repairs, ""),
        checked.stream().mapToLong(r -> r.observation().interventions()).sum(),
        checked.stream().mapToLong(r -> r.observation().unnecessaryIterations()).sum(),
        checked.stream().mapToLong(r -> r.observation().duplicateExecutions()).sum(),
        checked.stream().mapToLong(r -> r.observation().permissionViolations()).sum(),
        sum(checked, Observation::elapsedMillis), sum(checked, Observation::modelCalls), sum(checked, Observation::tokens));
  }
  private static double fraction(List<Result> results, java.util.function.Predicate<Result> predicate) {
    return results.stream().filter(predicate).count() / (double) results.size();
  }
  private static Measurement sum(List<Result> results, java.util.function.Function<Observation, Measurement> field) {
    long total = 0;
    for (var result : results) {
      var measurement = field.apply(result.observation());
      if (measurement.value() == null) return Measurement.unavailable(result.id() + ": " + measurement.reason());
      try { total = Math.addExact(total, measurement.value()); }
      catch (ArithmeticException overflow) { return Measurement.unavailable("Measurement sum overflow"); }
    }
    return Measurement.known(total);
  }
  private static void identity(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_.-]{1,128}"))
      throw new IllegalArgumentException("Bounded fixture identity required");
  }
}
