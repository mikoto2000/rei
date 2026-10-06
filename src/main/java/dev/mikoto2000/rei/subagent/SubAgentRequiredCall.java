package dev.mikoto2000.rei.subagent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact arguments and optional top-level output field contract; object key order is immaterial. */
public record SubAgentRequiredCall(String tool,String argumentsJson,String expectedOutputJson) {
  private static final JsonMapper MAPPER=JsonMapper.builder().build();
  private static final SubAgentResultParser PARSER=new SubAgentResultParser();
  public SubAgentRequiredCall(String tool,String argumentsJson) { this(tool,argumentsJson,null); }
  public SubAgentRequiredCall {
    if(tool==null||!tool.matches("[A-Za-z][A-Za-z0-9_]{0,99}")||argumentsJson==null||argumentsJson.length()>4096)
      throw new IllegalArgumentException("requiredToolCalls: invalid tool or oversized arguments");
    try {
      var value=PARSER.parse(argumentsJson);
      if(value==null||!value.isObject())throw new IllegalArgumentException();
      argumentsJson=MAPPER.writeValueAsString(value);
    }catch(RuntimeException error){throw new IllegalArgumentException("requiredToolCalls: arguments must be a JSON object");}
    if(expectedOutputJson!=null) {
      try {
        if(expectedOutputJson.length()>4096)throw new IllegalArgumentException();
        var expected=PARSER.parse(expectedOutputJson);
        if(!expected.isObject()||expected.isEmpty())throw new IllegalArgumentException();
        expectedOutputJson=MAPPER.writeValueAsString(expected);
      }catch(RuntimeException error){throw new IllegalArgumentException("requiredToolCalls: expectedOutput must be a nonempty bounded JSON object");}
    }
  }
  boolean matches(String observedTool,JsonNode arguments) {
    return tool.equals(observedTool)&&arguments!=null&&MAPPER.readTree(argumentsJson).equals(arguments);
  }
  boolean matchesOutput(String output,boolean truncated) {
    if(expectedOutputJson==null)return true;
    if(truncated)return false;
    try {
      var actual=PARSER.parse(output);
      var expected=MAPPER.readTree(expectedOutputJson);
      return actual.isObject()&&expected.properties().stream()
          .allMatch(field->field.getValue().equals(actual.get(field.getKey())));
    }catch(RuntimeException error){return false;}
  }
}
