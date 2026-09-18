package dev.mikoto2000.rei.subagent;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** Common envelope first, then the definition's optional result schema. */
public final class SubAgentResultValidator {
  public ValidationResult validate(SubAgentDefinition definition, JsonNode output) {
    List<ValidationError> errors = errors(SubAgentResultSchema.envelope().validate(output), "");
    if (errors.isEmpty() && definition.resultSchema() != null) {
      errors = errors(definition.resultSchema().validate(output.get("result")), "/result");
    }
    return ValidationResult.of(errors);
  }
  private List<ValidationError> errors(List<com.networknt.schema.Error> errors, String prefix) {
    // Do not expose library messages: enum/const/property errors may echo instance values.
    return errors.stream().limit(100).map(error -> new ValidationError(
        bounded(prefix + error.getInstanceLocation()), "Schema constraint violated: " + error.getKeyword())).toList();
  }
  private String bounded(String path) { return path.length() <= 256 ? path : path.substring(0, 256) + "…"; }
}
