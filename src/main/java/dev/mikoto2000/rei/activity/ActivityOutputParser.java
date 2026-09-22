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
      b -> b.schemaLoader(l -> l.fetchRemoteResources(false).block(iri -> true))).getSchema(MAPPER.readTree(SCHEMA));
  public ActivityExtractor.Result parse(String text,List<String> monitors) {
    if (text==null || text.length()>65536) throw new InvalidOutput(List.of(new ResultError("/","size")));
    try {
      var node=MAPPER.readTree(text);
      var errors=VALIDATOR.validate(node);
      if (!errors.isEmpty()) throw new InvalidOutput(errors.stream().limit(20).map(e -> new ResultError("/",e.getKeyword())).toList());
      var activities=new ArrayList<ActivityRecord.Activity>();
      for (var a:node.get("activities")) {
        if (!monitors.contains(a.get("monitor").asString())) throw new InvalidOutput(List.of(new ResultError("/activities/monitor","unknown_monitor")));
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
    private final List<ResultError> errors;
    public InvalidOutput(List<ResultError> errors) { super("Invalid activity structured output"); this.errors=List.copyOf(errors); }
    public List<ResultError> errors() { return errors; }
  }
}
