package dev.mikoto2000.rei.subagent;

import tools.jackson.databind.JsonNode;

public final class ExpectedOutputConditions {
  private ExpectedOutputConditions() { }
  public static JsonNode validate(String raw,SubAgentResultSchema schema) {
    String help="expectedOutput must be a nonempty JSON object of literal, exact-match result-field conditions, not JSON Schema or type placeholders. Put format requirements in task/constraints. For reviewer, {\"unverified\":[]} requires exactly no unverified items; it is not a generic output template.";
    JsonNode conditions;
    try {
      if(raw==null || raw.length()>16384)throw new IllegalArgumentException();
      conditions=new SubAgentResultParser().parse(raw);
    } catch(RuntimeException error){throw new IllegalArgumentException(help);}
    if(!conditions.isObject() || conditions.isEmpty())throw new IllegalArgumentException(help);
    if(schema!=null && !schema.validateConditions(conditions).isEmpty())
      throw new IllegalArgumentException(help+" Specified fields or values contradict the selected role resultSchema (check field names, types and enum values).");
    return conditions;
  }
}
