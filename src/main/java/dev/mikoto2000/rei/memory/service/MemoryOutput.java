package dev.mikoto2000.rei.memory.service;

import java.util.*;
import dev.mikoto2000.rei.subagent.*;
import dev.mikoto2000.rei.memory.model.*;
import tools.jackson.databind.JsonNode;

/** Reuses SubAgent strict parsing, bounded schema loading and offline Draft 2020-12 validation. */
public final class MemoryOutput {
  private static final SubAgentResultSchema CANDIDATES=load("memory-candidates");
  private static final SubAgentResultSchema RESOLUTION=load("memory-resolution");
  private static SubAgentResultSchema load(String name) {
    return SubAgentResultSchema.load(java.nio.file.Path.of("."),"classpath:/subagents/schemas/"+name+".schema.json");
  }
  public String candidateSchema() { return CANDIDATES.json(); }
  public String resolutionSchema() { return RESOLUTION.json(); }
  private JsonNode validate(String text,SubAgentResultSchema schema) {
    var json=new SubAgentResultParser().parse(text);
    if(!schema.validate(json).isEmpty()) throw new IllegalArgumentException("Memory output violates JSON schema");
    return json;
  }
  public List<MemoryCandidate> candidates(String text) {
    var result=new ArrayList<MemoryCandidate>();
    for(var row:validate(text,CANDIDATES).get("memories")) result.add(new MemoryCandidate(
        MemoryType.valueOf(row.get("type").asString()),MemoryScope.valueOf(row.get("scope").asString()),
        row.get("content").asString(),row.get("summary").asString(),row.get("confidence").asDouble(),
        row.get("importance").asDouble(),strings(row.get("sourceTurnIds")),strings(row.get("tags"))));
    return List.copyOf(result);
  }
  public MemoryResolution resolution(String text) {
    var row=validate(text,RESOLUTION);
    return new MemoryResolution(MemoryAction.valueOf(row.get("action").asString()),strings(row.get("targetIds")));
  }
  private List<String> strings(JsonNode array) {
    var result=new ArrayList<String>(); for(var item:array) result.add(item.asString()); return List.copyOf(result);
  }
}
