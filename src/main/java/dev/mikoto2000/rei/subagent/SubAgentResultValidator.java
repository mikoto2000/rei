package dev.mikoto2000.rei.subagent;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 補助エージェントへの委譲に必要な補助エージェント結果検証の窓口を扱う。
 *
 * <p>Common envelope first, then the definition's optional result schema.
 */
public final class SubAgentResultValidator {
  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public ValidationResult validate(SubAgentDefinition definition, JsonNode output) {
    List<ValidationError> errors = errors(SubAgentResultSchema.envelope().validate(output), "");
    if (errors.isEmpty() && definition.resultSchema() != null) {
      errors = errors(definition.resultSchema().validate(output.get("result")), "/result");
    }
    return ValidationResult.of(errors);
  }

  /** 補助エージェントへの委譲で用いるエラー一覧を返す。 */
  private List<ValidationError> errors(List<com.networknt.schema.Error> errors, String prefix) {
    // Do not expose library messages: enum/const/property errors may echo instance values.
    return errors.stream()
        .limit(100)
        .map(
            error ->
                new ValidationError(
                    bounded(prefix + error.getInstanceLocation()),
                    "Schema constraint violated: " + error.getKeyword()))
        .toList();
  }

  /** 利用先の上限を超えない量へ、指定された内容を調整する。 */
  private String bounded(String path) {
    return path.length() <= 256 ? path : path.substring(0, 256) + "…";
  }
}
