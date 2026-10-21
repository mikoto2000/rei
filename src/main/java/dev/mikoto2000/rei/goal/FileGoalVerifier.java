package dev.mikoto2000.rei.goal;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 目標の達成管理に必要なファイル目標確認の窓口を扱う。
 *
 * <p>Independent read-only predicate, never a model's completion claim.
 */
@Component
public class FileGoalVerifier {
  /** 完了を判定する窓口。 */
  private GoalCompletionGate completionGate;

  /** 目標の達成管理で使用する完了を判定する窓口を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setCompletionGate(GoalCompletionGate gate) {
    completionGate = gate;
  }

  /** 判定条件一覧有効状態。 */
  private boolean predicatesEnabled;

  /** 目標の達成管理で使用する判定条件一覧有効状態を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void setPredicatesEnabled(
      @org.springframework.beans.factory.annotation.Value("${rei.predicates.enabled:false}")
          boolean enabled) {
    predicatesEnabled = enabled;
  }

  /** 条件を満たさない操作を拒否するため、有効状態判定条件一覧の前提条件を確認する。 */
  public void requireEnabledPredicates(GoalRepository.Goal goal) {
    if (completionGate != null && completionGate.required(goal) && goal.completion() == null)
      throw new IllegalStateException(
          "Human completion definition required before running this Goal");
    if (!predicatesEnabled
        && (goal.criteria().stream().anyMatch(item -> item.predicateJson() != null)
            || goal.completion() != null
                && (goal.completion().requiredPredicates().stream()
                        .anyMatch(item -> item.predicateJson() != null)
                    || goal.completion().requirements().stream()
                        .anyMatch(
                            item -> item.required() && item.criterion().predicateJson() != null))))
      throw new IllegalStateException(
          "Enable rei.predicates.enabled before running a predicate Goal");
  }

  /**
   * 目標の達成管理で受け渡す確認結果を表す。
   *
   * @param satisfied 条件を満たしたかどうか
   * @param reason 理由
   */
  public record Verification(boolean satisfied, String reason) {}

  /** 時間計測。 */
  private dev.mikoto2000.rei.timing.TimingExecution timing;

  /** 目標の達成管理で使用する時間計測を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setTiming(dev.mikoto2000.rei.timing.TimingExecution timing) {
    this.timing = timing;
  }

  /** 要求された条件を満たすことを確認する。 */
  public Verification verify(GoalRepository.Goal goal) {
    if (timing == null || !timing.enabled() || goal.currentRunId() == null)
      return verifyObserved(goal);
    var span =
        timing.startSpan(
            goal.currentRunId(),
            goal.currentRunId(),
            null,
            null,
            dev.mikoto2000.rei.timing.TimingRecorder.Category.COMPLETION_VALIDATION);
    try {
      var result = verifyObserved(goal);
      span.finish(
          result.satisfied()
              ? dev.mikoto2000.rei.timing.TimingRecorder.Status.SUCCESS
              : dev.mikoto2000.rei.timing.TimingRecorder.Status.FAILED);
      return result;
    } catch (RuntimeException failure) {
      span.finish(dev.mikoto2000.rei.timing.TimingExecution.status(failure));
      throw failure;
    }
  }

  /** 要求された条件を満たすか判断するため、観測を確認する。 */
  private Verification verifyObserved(GoalRepository.Goal goal) {
    if (completionGate != null && completionGate.required(goal) && goal.completion() == null)
      return new Verification(false, "completion_definition_missing");
    if (completionGate == null && goal.completion() != null)
      return new Verification(false, "completion_gate_unavailable");
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (goal.criteria().isEmpty() || goal.criteria().size() > 16)
      return new Verification(false, "verification_unavailable");
    for (var criterion : goal.criteria()) {
      if (criterion.jsonCriterion()) {
        if (goal.projectRoot() == null) return new Verification(false, "verification_unavailable");
        try {
          var result = verifyJson(Path.of(goal.projectRoot()), criterion);
          if (!result.satisfied()) return result;
        } catch (IllegalArgumentException invalid) {
          return new Verification(false, "verification_unavailable");
        }
        continue;
      }
      if (criterion.sha256() == null
          || !criterion.sha256().matches("[a-f0-9]{64}")
          || goal.projectRoot() == null) return new Verification(false, "verification_unavailable");
      try {
        var result = verify(Path.of(goal.projectRoot()), criterion);
        if (!result.satisfied()) return result;
      } catch (IllegalArgumentException error) {
        return new Verification(false, "verification_unavailable");
      }
    }
    if (completionGate != null && completionGate.required(goal))
      return completionGate.verify(goal, this);
    return new Verification(
        true,
        goal.criteria().stream().anyMatch(GoalRepository.FileCriterion::jsonCriterion)
            ? "criteria_verified"
            : "file_digest_verified");
  }

  /** 要求された条件を満たすか判断するため、保存・受け渡しに使うJSON変換を確認する。 */
  private Verification verifyJson(Path root, GoalRepository.FileCriterion criterion) {
    if (Thread.currentThread().isInterrupted())
      return new Verification(false, "verification_cancelled");
    if (criterion.predicateJson() != null && !predicatesEnabled)
      return new Verification(false, "predicate_disabled");
    if (criterion.predicateJson() != null
        && (criterion.jsonPointer() != null || criterion.expectedJson() != null))
      return new Verification(false, "verification_unavailable");
    var predicate =
        criterion.predicateJson() == null
            ? null
            : dev.mikoto2000.rei.core.predicate.DeclarativePredicate.parse(
                criterion.predicateJson());
    var condition =
        predicate == null
            ? JsonFileGoalCondition.parse(criterion.jsonPointer(), criterion.expectedJson())
            : null;
    if (criterion.sha256() != null && !criterion.sha256().isEmpty())
      return new Verification(false, "verification_unavailable");
    var safe = fingerprint(root, criterion.relativeFile(), false);
    if (!safe.available()) return new Verification(false, safe.reason());
    Path file = root.resolve(criterion.relativeFile());
    try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      var buffer = ByteBuffer.allocate(65537);
      while (channel.read(buffer) >= 0) {
        if (Thread.currentThread().isInterrupted())
          return new Verification(false, "verification_cancelled");
        if (!buffer.hasRemaining()) return new Verification(false, "json_file_too_large");
      }
      var bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
      if (predicate != null) {
        var observed = predicate.evaluate(bytes);
        return new Verification(
            observed == dev.mikoto2000.rei.core.predicate.DeclarativePredicate.Result.SATISFIED,
            switch (observed) {
              case SATISFIED -> "predicate_verified";
              case UNSATISFIED -> "predicate_mismatch";
              case UNKNOWN -> "predicate_unknown";
            });
      }
      boolean matches = condition.matches(bytes);
      return new Verification(matches, matches ? "json_value_verified" : "json_value_mismatch");
    } catch (java.io.IOException invalid) {
      return new Verification(
          false,
          Thread.currentThread().isInterrupted() ? "verification_cancelled" : "json_file_invalid");
    }
  }

  /**
   * 目標の達成管理で受け渡す要求内容の照合値を表す。
   *
   * @param available 利用可能
   * @param sha256 内容の同一性を照合するSHA-256ハッシュ
   * @param reason 理由
   */
  public record Fingerprint(boolean available, String sha256, String reason) {}

  /** 目標の達成管理で用いる要求内容の照合値を返す。 */
  public Fingerprint fingerprint(Path root, String relativeFile) {
    return fingerprint(root, relativeFile, true);
  }

  /** 要求された条件を満たすことを確認する。 */
  public Verification verify(Path root, GoalRepository.FileCriterion criterion) {
    if (criterion.jsonCriterion()) return verifyJson(root, criterion);
    var observed = fingerprint(root, criterion.relativeFile(), criterion.sha256() != null);
    if (!observed.available()) return new Verification(false, observed.reason());
    if (criterion.sha256() == null) return new Verification(true, "file_exists");
    boolean matches = criterion.sha256().equals(observed.sha256());
    return new Verification(matches, matches ? "file_digest_verified" : "digest_mismatch");
  }

  /** 目標の達成管理で用いる要求内容の照合値を返す。 */
  private Fingerprint fingerprint(Path root, String relativeFile, boolean digestRequired) {
    try {
      GoalRepository.validateFile(relativeFile);
      if (!Files.isDirectory(root) || !root.toRealPath().equals(root))
        return new Fingerprint(false, null, "project_path_changed");
      Path current = root;
      for (var part : Path.of(relativeFile)) {
        current = current.resolve(part);
        if (Files.isSymbolicLink(current))
          return new Fingerprint(false, null, "symbolic_link_rejected");
      }
      if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS))
        return new Fingerprint(false, null, "file_missing_or_not_regular");
      if (!current.toRealPath().startsWith(root))
        return new Fingerprint(false, null, "outside_project");
      if (Files.size(current) > 1_048_576) return new Fingerprint(false, null, "file_too_large");
      if (!digestRequired) return new Fingerprint(true, null, "file_exists");
      var digest = MessageDigest.getInstance("SHA-256");
      long count = 0;
      try (var channel =
          FileChannel.open(current, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        var buffer = ByteBuffer.allocate(8192);
        while (channel.read(buffer) >= 0) {
          if (Thread.currentThread().isInterrupted())
            return new Fingerprint(false, null, "verification_cancelled");
          buffer.flip();
          count += buffer.remaining();
          if (count > 1_048_576) return new Fingerprint(false, null, "file_too_large");
          digest.update(buffer);
          buffer.clear();
        }
      }
      return new Fingerprint(
          true, HexFormat.of().formatHex(digest.digest()), "file_digest_observed");
    } catch (java.io.IOException | IllegalArgumentException error) {
      return new Fingerprint(false, null, "verification_unavailable");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
