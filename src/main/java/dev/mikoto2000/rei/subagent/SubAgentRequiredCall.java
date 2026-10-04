package dev.mikoto2000.rei.subagent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact JSON argument contract; object key order and whitespace are immaterial. */
public record SubAgentRequiredCall(String tool,String argumentsJson) {
  private static final JsonMapper MAPPER=JsonMapper.builder().build();
  private static final SubAgentResultParser PARSER=new SubAgentResultParser();
  public SubAgentRequiredCall {
    if(tool==null||!tool.matches("[A-Za-z][A-Za-z0-9_]{0,99}")||argumentsJson==null||argumentsJson.length()>4096)
      throw new IllegalArgumentException("requiredToolCalls: invalid tool or oversized arguments");
    try {
      var value=PARSER.parse(argumentsJson);
      if(value==null||!value.isObject())throw new IllegalArgumentException();
      argumentsJson=MAPPER.writeValueAsString(value);
    }catch(RuntimeException error){throw new IllegalArgumentException("requiredToolCalls: arguments must be a JSON object");}
  }
  boolean matches(String observedTool,JsonNode arguments) {
    return tool.equals(observedTool)&&arguments!=null&&MAPPER.readTree(argumentsJson).equals(arguments);
  }
}
