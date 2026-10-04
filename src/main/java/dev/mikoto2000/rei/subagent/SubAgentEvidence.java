package dev.mikoto2000.rei.subagent;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Per-run observations captured by the runner, never accepted from model claims. */
final class SubAgentEvidence {
  private record Observation(String tool, String hash, String output,JsonNode arguments) { }
  private final Map<String,Observation> observations = new LinkedHashMap<>();
  private final JsonMapper mapper = JsonMapper.builder().build();
  synchronized String capture(String tool, String input, String output) {
    if (observations.size() >= 64 || output == null) throw new IllegalStateException("Evidence capture limit or invalid tool output");
    String id = UUID.randomUUID().toString();
    String retained = output.substring(0, Math.min(16384, output.length()));
    String hash = hash(output);
    JsonNode arguments=null;
    if(input!=null&&input.length()<=16384)try{arguments=new SubAgentResultParser().parse(input);}catch(RuntimeException invalid){/* Unparseable input cannot satisfy an exact call contract. */}
    observations.put(id, new Observation(tool, hash, retained,arguments));
    return mapper.writeValueAsString(Map.of("evidenceId",id,"tool",tool,"inputSha256",hash(input),
        "outputSha256",hash,"output",retained,"truncated",retained.length()!=output.length()));
  }
  synchronized ValidationResult validate(List<String> requiredTools, JsonNode json) {
    return validate(requiredTools,List.of(),json);
  }
  synchronized ValidationResult validate(List<String> requiredTools,List<SubAgentRequiredCall> requiredCalls, JsonNode json) {
    var errors = new ArrayList<ValidationError>();
    JsonNode evidence = json.path("result").path("evidence");
    if (!evidence.isArray() || evidence.size() > 64) {
      return ValidationResult.of(List.of(new ValidationError("/result/evidence", "Expected bounded evidence array")));
    }
    var seen = new HashSet<String>(); var supported = new HashSet<String>();
    var cited=new ArrayList<Observation>();
    for (int i=0; i<evidence.size(); i++) {
      JsonNode claim = evidence.get(i);
      String path = "/result/evidence/"+i;
      if (!claim.isObject() || claim.size()!=4 || !text(claim,"evidenceId",64) || !text(claim,"tool",100)
          || !text(claim,"outputSha256",64) || !text(claim,"quote",2048)) {
        errors.add(new ValidationError(path,"Expected evidenceId, tool, outputSha256 and bounded nonblank quote")); continue;
      }
      String id = claim.get("evidenceId").asString();
      Observation actual = observations.get(id);
      if (!seen.add(id) || actual==null || !actual.tool().equals(claim.get("tool").asString())
          || !actual.hash().equals(claim.get("outputSha256").asString())
          || !actual.output().contains(claim.get("quote").asString())) {
        errors.add(new ValidationError(path,"Evidence does not match a unique observation from this run")); continue;
      }
      supported.add(actual.tool());
      cited.add(actual);
    }
    if("SUCCESS".equals(json.path("status").asString()))for(int index=0;index<requiredCalls.size();index++) {
      var required=requiredCalls.get(index);
      if(cited.stream().noneMatch(actual->required.matches(actual.tool(),actual.arguments())))
        errors.add(new ValidationError("/status","SUCCESS requires cited observation for requiredToolCalls["+index+"]"));
    }
    if ("SUCCESS".equals(json.path("status").asString()) && !supported.containsAll(requiredTools)) {
      errors.add(new ValidationError("/status","SUCCESS requires cited observations for all required evidence tools"));
    }
    return ValidationResult.of(errors);
  }
  private static boolean text(JsonNode node, String key, int max) {
    var value = node.get(key);
    return value!=null && value.isString() && !value.asString().isBlank() && value.asString().length()<=max;
  }
  private static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
  }
}
