package dev.mikoto2000.rei.activity;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.networknt.schema.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

/** Same offline JSON Schema validator stack as SubAgent output validation, with sanitized errors. */
public final class ActivityOutputParser {
  private static final JsonMapper MAPPER=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  public static final String SCHEMA=resource();
  private static final Schema VALIDATOR=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
      b -> b.schemaRegistryConfig(SchemaRegistryConfig.builder().pathType(com.networknt.schema.path.PathType.JSON_POINTER).build())
          .schemaLoader(l -> l.fetchRemoteResources(false).block(iri -> true))).getSchema(MAPPER.readTree(SCHEMA));
  /** Constrain provider generation to the actual display IDs in this capture. */
  public static String schemaForMonitors(List<String> monitors) {
    if (monitors.isEmpty()) throw new IllegalArgumentException("Missing monitors");
    var schema=MAPPER.readTree(SCHEMA);
    var monitor=(tools.jackson.databind.node.ObjectNode)schema.at("/properties/activities/items/properties/monitor");
    var allowed=monitor.putArray("enum");
    monitors.forEach(allowed::add);
    return MAPPER.writeValueAsString(schema);
  }
  public ActivityExtractor.Result parse(String text,List<String> monitors) {
    if (text==null || text.isBlank()) throw new InvalidOutput(List.of(new ResultError("/","empty_output")));
    if (text.length()>65536) throw new InvalidOutput(List.of(new ResultError("/","size")));
    try {
      var node=MAPPER.readTree(text);
      var errors=VALIDATOR.validate(node);
      if (!errors.isEmpty()) throw new InvalidOutput(errors.stream().limit(20).map(e -> new ResultError(e.getInstanceLocation().toString(),e.getKeyword())).toList());
      var activities=new ArrayList<ActivityRecord.Activity>();
      for (var a:node.get("activities")) {
        if (!monitors.contains(a.get("monitor").asString())) throw new InvalidOutput(List.of(new ResultError("/activities/"+activities.size()+"/monitor","unknown_monitor")));
        activities.add(new ActivityRecord.Activity(a.get("monitor").asString(),a.get("type").asString(),a.get("application").asString(),a.get("service").asString(),a.get("contentTitle").asString(),a.get("projectCandidate").asString()));
      }
      return new ActivityExtractor.Result(new ActivityRecord.Inference(node.get("summary").asString(),activities),node.get("confidence").asDouble());
    } catch (InvalidOutput e) { throw e; }
    catch (Exception e) { throw new InvalidOutput(List.of(new ResultError("/","invalid_json"))); }
  }
  private static String resource() {
    try(var stream=ActivityOutputParser.class.getResourceAsStream("/activity/extraction.schema.json")) {
      return new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8);
    } catch(Exception e) { throw new IllegalStateException("Activity schema unavailable"); }
  }
  public record ResultError(String path,String code) {}
  public static final class InvalidOutput extends IllegalArgumentException {
    private static final Set<String> SAFE_CODES=Set.of("size","empty_output","invalid_json","unknown_monitor",
        "invalid_response","empty_response","multiple_results","unexpected_tool_calls","output_limit",
        "type","required","additionalProperties","minimum","maximum","minLength","maxLength","maxItems");
    private final List<ResultError> errors;
    public InvalidOutput(List<ResultError> errors) { super("Invalid activity structured output"); this.errors=List.copyOf(errors); }
    public List<ResultError> errors() { return errors; }
    /** Log only schema-owned paths and known codes, never provider text or unknown property names. */
    public String diagnostic() {
      return errors.stream().limit(5).map(e -> safePath(e.path())+":"+
          (e.code()!=null && SAFE_CODES.contains(e.code()) ? e.code() : "validation_error"))
          .distinct().collect(java.util.stream.Collectors.joining(", "));
    }
    private static String safePath(String path) {
      return path!=null && path.matches("/(summary|confidence|activities(?:/[0-9]{1,4}(?:/(?:monitor|type|application|service|contentTitle|projectCandidate))?)?)") ? path : "/";
    }
  }
}
