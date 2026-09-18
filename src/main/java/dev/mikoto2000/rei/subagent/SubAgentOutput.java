package dev.mikoto2000.rei.subagent;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Validated model envelope; its task status is distinct from execution status. */
public record SubAgentOutput(Status status, String summary, JsonNode result, List<String> warnings) {
  public enum Status { SUCCESS, FAILURE, PARTIAL }
  public SubAgentOutput { result = result.deepCopy(); warnings = List.copyOf(warnings); }
  @Override public JsonNode result() { return result.deepCopy(); }
  static SubAgentOutput fromValidated(JsonNode json) {
    return new SubAgentOutput(Status.valueOf(json.get("status").asString()), json.get("summary").asString(),
        json.get("result"), json.get("warnings").valueStream().map(JsonNode::asString).toList());
  }
}
