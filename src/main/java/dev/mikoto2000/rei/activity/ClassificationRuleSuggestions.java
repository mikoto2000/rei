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
  /** Only application-owned diagnostics belong in this exception, never model output or API bodies. */
  static final class Failure extends IllegalArgumentException {
    Failure(String code,String detail){super(code+": "+detail);}
  }
  static Failure failure(String code,String detail){return new Failure(code,detail);}
  public ClassificationRuleSuggestions(ClassificationToolkit toolkit,Model model){this.toolkit=toolkit;this.model=model;}
  public String suggest(boolean entertainment) {
    if(!toolkit.properties().getClassification().getRuleSuggestion().isEnabled())return "ルール候補生成は無効です。";
    String stage="候補選択";
    try {
      int minimum=toolkit.properties().getClassification().getRuleSuggestion().getMinimumSamples();
      var candidate=toolkit.candidates(entertainment?"uncertain":"unknown").stream()
          .filter(c->c.count()>=minimum && c.outcomes().size()==1 && (entertainment || c.visionSuccess()>=minimum))
          .filter(c->!covered(c,entertainment)).findFirst();
      if(candidate.isEmpty())return "候補なし（一貫したサンプル不足、または既存ルールで対応済み）。";
      var c=candidate.get();
      stage="入力準備";
      var input=Map.of("ruleType",entertainment?"ENTERTAINMENT":"CLASSIFICATION","sample",c,"existingRules",toolkit.rules().snapshot().rules().stream().limit(100).map(r->Map.of("id",r.id(),"type",r.type(),"priority",r.priority(),"match",r.match(),"classify",r.classify())).toList());
      String inputJson=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(input);
      if(inputJson.length()>65536)throw failure("INPUT_TOO_LARGE","入力が65536文字を超えています。");
      if(new dev.mikoto2000.rei.memory.util.SensitiveInfoDetector().containsSensitiveInfo(inputJson))throw failure("SENSITIVE_INPUT","入力に機密情報の可能性がある文字列を検出しました。");
      stage="LLM呼び出し";
      String output=model.propose(inputJson,schema(entertainment));
      stage="応答検証";
      var proposal=validate(output,entertainment);
      var r=proposal.rule();
      stage="サンプル整合性検証";
      if(!r.matches(c.process(),c.title(),c.service(),c.application(),c.content(),c.category()))throw failure("SAMPLE_MISMATCH","提案のmatch条件が対象サンプルに一致しません。");
      if(!entertainment && !r.classify().get("category").equals(c.category()))throw failure("EVIDENCE_CONFLICT","提案のcategoryが観測結果と一致しません。");
      stage="既存ルール再確認";
      if(covered(c,entertainment))return "候補なし（生成中に既存ルールで対応済みになりました）。";
      toolkit.suggested(entertainment,1);
      return "未適用の候補です。内容を確認して設定ファイルへ手動追加してください。\n# proposalConfidence: "+proposal.confidence()+"\n# "+proposal.rationale().replaceAll("[\\p{Cntrl}]"," ")+"\n"+proposal.yaml();
    }catch(Exception e){return "ルール候補を生成できませんでした。有効ルールは変更していません。\n失敗段階: "+stage+"\n理由: "+diagnostic(e);}
  }
  private static String diagnostic(Exception e) {
    if(e instanceof Failure)return e.getMessage();
    var types=new ArrayList<String>();
    var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
    for(Throwable cause=e;cause!=null && seen.add(cause) && types.size()<8;cause=cause.getCause()) {
      String type=cause.getClass().getSimpleName();
      if(cause instanceof org.springframework.web.client.RestClientResponseException http)type+=" (HTTP "+http.getStatusCode().value()+")";
      types.add(type);
    }
    return "UNEXPECTED_ERROR: "+String.join(" -> ",types);
  }
  private boolean covered(ClassificationTelemetryRepository.Candidate c,boolean entertainment) {
    if(entertainment)return toolkit.rules().entertainment(c.service(),c.application(),c.title(),c.content(),c.category()).disposition()!=EntertainmentDisposition.UNCERTAIN;
    var fg=new ForegroundWindow(c.process(),1,c.title(),"proposal");
    return toolkit.classify(new ActivityEvidence(java.time.Instant.now(),fg,List.of(),"","",List.of(),null)).usable(toolkit.properties().getDetection().getSkipVisionConfidence());
  }
  public Proposal validate(String text,boolean entertainment) {
    if(text==null || text.isBlank())throw failure("EMPTY_RESPONSE","LLMの応答が空です。");
    if(text.length()>16384)throw failure("RESPONSE_TOO_LARGE","応答が16384文字を超えています。");
    tools.jackson.databind.JsonNode node;
    try{node=JSON.readTree(text);}catch(Exception e){throw failure("INVALID_JSON","応答を単一のJSONとして解析できません。");}
    var errors=VALIDATOR.validate(node);
    if(!errors.isEmpty())throw failure("SCHEMA_VALIDATION_FAILED","JSONスキーマ不一致: "+errors.stream()
        .map(e->e.getSchemaLocation()+" ("+e.getKeyword()+")").distinct().sorted().limit(5).collect(java.util.stream.Collectors.joining(", ")));
    try {
      var map=OperationalRules.map(new com.fasterxml.jackson.databind.ObjectMapper().readValue(text,Map.class));
      if(!Objects.equals(map.get("ruleType"),entertainment?"ENTERTAINMENT":"CLASSIFICATION"))throw failure("WRONG_RULE_TYPE","ruleTypeが要求した種類と一致しません。");
      var match=new LinkedHashMap<>(OperationalRules.map(map.get("match")));match.values().removeIf(Objects::isNull);
      var classify=new LinkedHashMap<>(OperationalRules.map(map.get("classify")));classify.values().removeIf(Objects::isNull);
      var rule=Map.of("id",map.get("id"),"priority",map.get("priority"),"match",match,"classify",classify);
      String yaml=new Yaml().dump(Map.of(entertainment?"entertainmentRules":"classificationRules",List.of(rule)));
      OperationalRules.Rule compiled;
      try{compiled=OperationalRules.compile(yaml,true).getFirst();}
      catch(java.util.regex.PatternSyntaxException e){throw failure("INVALID_REGEX","matchの正規表現が不正です。");}
      catch(OperationalRules.InvalidDefinition e){throw failure("INVALID_RULE_DEFINITION",e.getMessage());}
      catch(IllegalArgumentException e){throw failure("INVALID_RULE_DEFINITION","match/classifyの条件が不正、または正規表現が過剰に広い・許可されていない形式です。scopeとcontextの両方が必要です。");}
      if(toolkit.rules().snapshot().rules().stream().anyMatch(r->r.id().equals(compiled.id())))throw failure("EXISTING_RULE_ID","既存ルールとidが重複しています。");
      if(toolkit.rules().snapshot().rules().stream().anyMatch(r->r.type().equals(compiled.type()) && r.match().equals(compiled.match())))throw failure("EXISTING_RULE_MATCH","同じ種類・match条件の既存ルールがあります。");
      String rationale=Objects.toString(map.get("rationale"));
      if(new dev.mikoto2000.rei.memory.util.SensitiveInfoDetector().containsSensitiveInfo(yaml+rationale))throw failure("SENSITIVE_PROPOSAL","提案に機密情報の可能性がある文字列を検出しました。");
      double confidence=OperationalRules.number(map.get("proposalConfidence"));if(confidence<.7)throw failure("LOW_PROPOSAL_CONFIDENCE","proposalConfidence="+confidence+" は最低値0.7未満です。");
      return new Proposal(compiled,confidence,rationale,yaml);
    }catch(Failure e){throw e;}catch(Exception e){throw new IllegalArgumentException("Proposal validation failed",e);}
  }
  static String schema(boolean entertainment) {
    try {
      var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
      var root=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(SCHEMA);
      var properties=root.withObject("/properties");
      properties.withObject("/ruleType").putArray("enum").add(entertainment?"ENTERTAINMENT":"CLASSIFICATION");
      var match=properties.withObject("/match/properties");
      var classify=properties.withObject("/classify/properties");
      for(String field:entertainment?List.of("category","service","categoryConfidence","serviceConfidence"):List.of("entertainmentDisposition","confidence"))
        classify.putObject(field).put("type","null");
      if(entertainment) {
        classify.withObject("/entertainmentDisposition").put("type","string").putArray("enum").add("ENTERTAINMENT").add("NON_ENTERTAINMENT").add("UNCERTAIN");
        classify.putObject("confidence").put("type","number").put("minimum",0).put("maximum",1);
      }else {
        for(String field:List.of("serviceRegex","contentRegex"))match.putObject(field).put("type","null");
        for(String field:List.of("processRegex","titleRegex"))match.withObject("/"+field).put("type","string").put("minLength",1);
        var category=classify.withObject("/category");category.put("type","string");
        var values=category.putArray("enum");OperationalRules.CATEGORIES.stream().sorted().forEach(values::add);
      }
      return mapper.writeValueAsString(root);
    }catch(Exception e){throw new IllegalStateException("Proposal schema unavailable",e);}
  }
  private static String resource(){try(var in=ClassificationRuleSuggestions.class.getResourceAsStream("/activity/rule-suggestion.schema.json")){return new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("Proposal schema unavailable",e);}}
}
