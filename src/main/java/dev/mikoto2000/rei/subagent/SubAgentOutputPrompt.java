package dev.mikoto2000.rei.subagent;

/** Advertises the same schemas used locally without coupling validation to prompt construction. */
final class SubAgentOutputPrompt {
  private SubAgentOutputPrompt() { }
  static String instructions(SubAgentDefinition definition) {
    return "\n\nReturn exactly one JSON object. Do not output Markdown or code fences."
        + " Do not output explanatory text before or after the JSON."
        + " The final result must conform to this envelope schema:\n" + SubAgentResultSchema.envelope().json()
        + (definition.resultSchema() == null ? "" : "\nThe result property must conform to this schema:\n" + definition.resultSchema().json());
  }
}
