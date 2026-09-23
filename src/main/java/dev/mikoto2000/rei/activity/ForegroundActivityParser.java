package dev.mikoto2000.rei.activity;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.networknt.schema.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

/** A single foreground inference; OS observations and monitor identity never come from the model. */
public final class ForegroundActivityParser {
  private static final JsonMapper MAPPER=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  public static final String SCHEMA=resource();
  private static final Schema VALIDATOR=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
      b->b.schemaRegistryConfig(SchemaRegistryConfig.builder().pathType(com.networknt.schema.path.PathType.JSON_POINTER).build())
          .schemaLoader(l->l.fetchRemoteResources(false).block(iri->true))).getSchema(MAPPER.readTree(SCHEMA));
  public ActivityExtractor.Result parse(String text,String monitor) {
    if(text==null || text.isBlank())throw invalid("empty_output");
    if(text.length()>8192)throw invalid("size");
    try {
      var n=MAPPER.readTree(text);var errors=VALIDATOR.validate(n);
      if(!errors.isEmpty())throw new ActivityOutputParser.InvalidOutput(errors.stream().limit(20).map(e->new ActivityOutputParser.ResultError(e.getInstanceLocation().toString(),e.getKeyword())).toList());
      var activity=new ActivityRecord.Activity(monitor,n.get("category").asString(),value(n,"application"),value(n,"service"),value(n,"contentCandidate"),value(n,"projectCandidate"));
      return new ActivityExtractor.Result(new ActivityRecord.Inference(n.get("summary").asString(),List.of(activity)),n.get("confidence").asDouble());
    }catch(ActivityOutputParser.InvalidOutput e){throw e;}
    catch(Exception e){throw invalid("invalid_json");}
  }
  private static String value(tools.jackson.databind.JsonNode n,String key){return n.get(key).isNull()?"":n.get(key).asString();}
  private static ActivityOutputParser.InvalidOutput invalid(String code){return new ActivityOutputParser.InvalidOutput(List.of(new ActivityOutputParser.ResultError("/",code)));}
  private static String resource() {
    try(var stream=ForegroundActivityParser.class.getResourceAsStream("/activity/foreground-classification.schema.json")){
      return new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8);
    }catch(Exception e){throw new IllegalStateException("Foreground schema unavailable");}
  }
}
