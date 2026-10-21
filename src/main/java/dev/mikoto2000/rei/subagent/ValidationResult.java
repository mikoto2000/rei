package dev.mikoto2000.rei.subagent;

import java.util.List;

/**
 * 補助エージェントへの委譲で受け渡す検証結果を表す。
 *
 * @param valid 有効
 * @param errors エラー一覧
 */
public record ValidationResult(boolean valid, List<ValidationError> errors) {
  /** 受け取った情報からValidationResultを作成する。 */
  public ValidationResult {
    errors = List.copyOf(errors);
    if (valid != errors.isEmpty())
      throw new IllegalArgumentException("valid must agree with errors");
  }

  /** 補助エージェントへの委譲で受け渡す情報を、指定された内容から作成する。 */
  public static ValidationResult of(List<ValidationError> errors) {
    return new ValidationResult(errors.isEmpty(), errors);
  }
}
