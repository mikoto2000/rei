package dev.mikoto2000.rei.activity;

import java.util.*;
import org.yaml.snakeyaml.Yaml;
import com.networknt.schema.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

/** One bounded proposal per explicit command. No file writer/reload capability is exposed to the model. */
public final class ClassificationRuleSuggestions {
  @FunctionalInterface public interface Model {String propose(String input,String schema) throws Exception;}
  private final ClassificationToolkit toolkit;private final Model model;
  private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  static final String SCHEMA=resource();
  private static final Schema VALIDATOR=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,b->b.schemaLoader(l->l.fetchRemoteResources(false).block(iri->true))).getSchema(JSON.readTree(SCHEMA));
  public record Proposal(OperationalRules.Rule rule,double confidence,String rationale,String yaml) {}
  public ClassificationRuleSuggestions(ClassificationToolkit toolkit,Model model){this.toolkit=toolkit;this.model=model;}
  public String suggest(boolean entertainment) {
    if(!toolkit.properties().getClassification().getRuleSuggestion().isEnabled())return "ルール候補生成は無効です。";
    try {
      int minimum=toolkit.properties().getClassification().getRuleSuggestion().getMinimumSamples();
      var candidate=toolkit.candidates(entertainment?"uncertain":"unknown").stream()
          .filter(c->c.count()>=minimum && c.outcomes().size()==1 && (entertainment || c.visionSuccess()>=minimum))
          .filter(c->!covered(c,entertainment)).findFirst();
      if(candidate.isEmpty())return "候補なし（一貫したサンプル不足、または既存ルールで対応済み）。";
      var c=candidate.get();
      var input=Map.of("ruleType",entertainment?"ENTERTAINMENT":"CLASSIFICATION","sample",c,"existingRules",toolkit.rules().snapshot().rules().stream().limit(100).map(r->Map.of("id",r.id(),"type",r.type(),"priority",r.priority(),"match",r.match(),"classify",r.classify())).toList());
      String inputJson=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(input);
      if(inputJson.length()>65536 || new dev.mikoto2000.rei.memory.util.SensitiveInfoDetector().containsSensitiveInfo(inputJson))throw new IllegalArgumentException("Unsafe or oversized proposal input");
      var proposal=validate(model.propose(inputJson,SCHEMA),entertainment);
      var r=proposal.rule();
      if(!r.matches(c.process(),c.title(),c.service(),c.application(),c.content(),c.category()))throw new IllegalArgumentException("Proposal does not match sample");
      if(!entertainment && !r.classify().get("category").equals(c.category()))throw new IllegalArgumentException("Proposal conflicts with evidence");
      if(covered(c,entertainment))return "候補なし（生成中に既存ルールで対応済みになりました）。";
      toolkit.suggested(entertainment,1);
      return "未適用の候補です。内容を確認して設定ファイルへ手動追加してください。\n# proposalConfidence: "+proposal.confidence()+"\n# "+proposal.rationale().replaceAll("[\\p{Cntrl}]"," ")+"\n"+proposal.yaml();
    }catch(Exception e){return "ルール候補を生成できませんでした（LLM失敗、検証失敗、または既存ルールとの競合）。有効ルールは変更していません。";}
  }
  private boolean covered(ClassificationTelemetryRepository.Candidate c,boolean entertainment) {
    if(entertainment)return toolkit.rules().entertainment(c.service(),c.application(),c.title(),c.content(),c.category()).disposition()!=EntertainmentDisposition.UNCERTAIN;
    var fg=new ForegroundWindow(c.process(),1,c.title(),"proposal");
    return toolkit.classify(new ActivityEvidence(java.time.Instant.now(),fg,List.of(),"","",List.of(),null)).usable(toolkit.properties().getDetection().getSkipVisionConfidence());
  }
  public Proposal validate(String text,boolean entertainment) {
    try {
      if(text==null || text.length()>16384)throw new IllegalArgumentException("Invalid proposal");
      var node=JSON.readTree(text);if(!VALIDATOR.validate(node).isEmpty())throw new IllegalArgumentException("Invalid proposal schema");
      var map=OperationalRules.map(new com.fasterxml.jackson.databind.ObjectMapper().readValue(text,Map.class));
      if(!Objects.equals(map.get("ruleType"),entertainment?"ENTERTAINMENT":"CLASSIFICATION"))throw new IllegalArgumentException("Wrong rule type");
      var match=new LinkedHashMap<>(OperationalRules.map(map.get("match")));match.values().removeIf(Objects::isNull);
      var classify=new LinkedHashMap<>(OperationalRules.map(map.get("classify")));classify.values().removeIf(Objects::isNull);
      var rule=Map.of("id",map.get("id"),"priority",map.get("priority"),"match",match,"classify",classify);
      String yaml=new Yaml().dump(Map.of(entertainment?"entertainmentRules":"classificationRules",List.of(rule)));
      var compiled=OperationalRules.compile(yaml,true).getFirst();
      if(toolkit.rules().snapshot().rules().stream().anyMatch(r->r.id().equals(compiled.id()) || r.type().equals(compiled.type()) && r.match().equals(compiled.match())))throw new IllegalArgumentException("Existing rule conflict");
      String rationale=Objects.toString(map.get("rationale"));
      if(new dev.mikoto2000.rei.memory.util.SensitiveInfoDetector().containsSensitiveInfo(yaml+rationale))throw new IllegalArgumentException("Sensitive proposal");
      double confidence=OperationalRules.number(map.get("proposalConfidence"));if(confidence<.7)throw new IllegalArgumentException("Low proposal confidence");
      return new Proposal(compiled,confidence,rationale,yaml);
    }catch(Exception e){throw new IllegalArgumentException("Invalid or conflicting proposal");}
  }
  private static String resource(){try(var in=ClassificationRuleSuggestions.class.getResourceAsStream("/activity/rule-suggestion.schema.json")){return new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("Proposal schema unavailable",e);}}
}
