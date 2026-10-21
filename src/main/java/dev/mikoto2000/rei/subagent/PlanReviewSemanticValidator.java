package dev.mikoto2000.rei.subagent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 補助エージェントへの委譲に必要な計画レビュー意味による検証の窓口を扱う。
 *
 * <p>Independent mechanical checks after schema and snapshot citation validation. Not an LLM
 * verdict.
 */
public final class PlanReviewSemanticValidator {
  /** 補助エージェントへの委譲における補助の選択肢。 */
  public enum Support {
    /** 対応済み。 */
    SUPPORTED,
    /** PARTIALLY対応済み。 */
    PARTIALLY_SUPPORTED,
    /** 未対応。 */
    UNSUPPORTED,
    /** 未確認。 */
    UNVERIFIED
  }

  /**
   * 補助エージェントへの委譲で受け渡す評価結果を表す。
   *
   * @param support 補助
   * @param truthVerified 確定情報確認済み
   * @param issues 課題一覧
   */
  public record Verdict(Support support, boolean truthVerified, List<ValidationError> issues) {
    /** 受け取った情報からVerdictを作成する。 */
    public Verdict {
      issues = List.copyOf(issues);
    }
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public Verdict validate(JsonNode wrapper) {
    var errors = new ArrayList<ValidationError>();
    var seen = new HashMap<String, String>();
    var paths = new HashSet<String>();
    var quotes = new ArrayList<String>();
    for (var reference : wrapper.path("references")) paths.add(reference.path("path").asString());
    for (var reference : wrapper.path("references")) quotes.add(reference.path("quote").asString());
    boolean literal = true;
    for (var finding : wrapper.path("findings")) {
      String message = finding.path("message").asString();
      String location = finding.path("location").asString();
      String severity = finding.path("severity").asString();
      String previous = seen.putIfAbsent(location + "\n" + message, severity);
      if (previous != null)
        errors.add(
            new ValidationError(
                "/findings",
                previous.equals(severity)
                    ? "Duplicate finding"
                    : "Contradictory finding severity"));
      literal &= !message.isBlank() && quotes.stream().anyMatch(q -> q.contains(message));
      if (location.isBlank()
          || paths.stream()
              .noneMatch(path -> location.equals(path) || location.startsWith(path + ":")))
        errors.add(
            new ValidationError("/findings/location", "Finding has no cited source location"));
    }
    // SUCCESS that explicitly reports unverified work cannot claim full support.
    Support support =
        !errors.isEmpty()
            ? Support.UNSUPPORTED
            : paths.isEmpty()
                ? Support.UNVERIFIED
                : literal
                        && !wrapper.path("findings").isEmpty()
                        && wrapper.path("unverified").isEmpty()
                    ? Support.SUPPORTED
                    : Support.PARTIALLY_SUPPORTED;
    return new Verdict(support, false, errors);
  }
}
