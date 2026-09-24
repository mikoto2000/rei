package dev.mikoto2000.rei.activity;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class OperationalSuggestionTest {
  @TempDir Path dir;
  @Test void validatedProposalIsYamlAndNeverChangesRules() throws Exception {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("s.db"));
    var toolkit=new ClassificationToolkit(new ActivityProperties(),new OperationalRules(dir.resolve("rules.yaml")),new ClassificationTelemetryRepository(ds),Clock.fixed(ActivityEvidenceClassifierTest.NOW,ZoneOffset.UTC));
    for(int i=0;i<3;i++){toolkit.observe(toolkit.decorate(OperationalTelemetryTest.record("r"+i,"firefox","Hugging Face","unknown",false)));toolkit.observe(toolkit.decorate(OperationalTelemetryTest.record("r"+i,"firefox","Hugging Face","research",true)));}
    var before=toolkit.rules().snapshot();
    var proposer=new ClassificationRuleSuggestions(toolkit,(input,schema)->{assertEquals(ClassificationRuleSuggestions.schema(false),schema);return proposal("Hugging Face","research");});
    String result=proposer.suggest(false);assertTrue(result.contains("classificationRules:"));assertTrue(result.contains("proposalConfidence"));assertSame(before,toolkit.rules().snapshot());assertFalse(Files.exists(dir.resolve("rules.yaml")));
    assertThrows(IllegalArgumentException.class,()->proposer.validate(proposal(".*","research"),false));
    assertThrows(IllegalArgumentException.class,()->proposer.validate(proposal("[","research"),false));
    assertThrows(IllegalArgumentException.class,()->proposer.validate(proposal("Hugging Face","bogus"),false));
  }
  @Test void insufficientSamplesNeverCallModel() {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("empty.db"));
    var toolkit=new ClassificationToolkit(new ActivityProperties(),new OperationalRules(dir.resolve("rules.yaml")),new ClassificationTelemetryRepository(ds),Clock.systemUTC());
    var proposer=new ClassificationRuleSuggestions(toolkit,(input,schema)->{fail("No eligible samples");return "";});
    assertTrue(proposer.suggest(true).contains("候補なし"));
  }
  @Test void entertainmentProposalNeedsContextAndDoesNotApply() throws Exception {
    var toolkit=toolkit("ent.db");
    for(int i=0;i<3;i++)toolkit.observe(toolkit.decorate(video("v"+i,"JVM compilation deep dive","media")));
    assertEquals(3,toolkit.candidates("uncertain").getFirst().count());
    var before=toolkit.rules().snapshot();
    String output="""
      {"ruleType":"ENTERTAINMENT","id":"jvm-learning","priority":250,
       "match":{"processRegex":null,"titleRegex":"JVM compilation","serviceRegex":"^YouTube$","contentRegex":null},
       "classify":{"category":null,"service":null,"categoryConfidence":null,"serviceConfidence":null,"entertainmentDisposition":"NON_ENTERTAINMENT","confidence":0.9},
       "proposalConfidence":0.85,"rationale":"Three consistent title samples"}
      """;
    var proposer=new ClassificationRuleSuggestions(toolkit,(input,schema)->{
      assertEquals(ClassificationRuleSuggestions.schema(true),schema);
      var json=tools.jackson.databind.json.JsonMapper.builder().build();
      var validator=com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12).getSchema(json.readTree(schema));
      assertTrue(validator.validate(json.readTree(output)).isEmpty());
      assertFalse(validator.validate(json.readTree(output.replace("\"category\":null","\"category\":\"research\""))).isEmpty());
      assertFalse(validator.validate(json.readTree(output.replace("\"confidence\":0.9","\"confidence\":null"))).isEmpty());
      return output;
    });
    assertTrue(proposer.suggest(true).contains("entertainmentRules:"));assertSame(before,toolkit.rules().snapshot());
    assertThrows(IllegalArgumentException.class,()->proposer.validate(output.replace("NON_ENTERTAINMENT","INVALID"),true));
    assertThrows(IllegalArgumentException.class,()->proposer.validate(output.replace("JVM compilation",".*"),true));
  }
  @Test void mixedEvidenceAndExistingCoverageSuppressCalls() throws Exception {
    var toolkit=toolkit("mixed.db");
    for(int i=0;i<3;i++)toolkit.observe(toolkit.decorate(video("v"+i,"JVM compilation deep dive",i==0?"other":"media")));
    var proposer=new ClassificationRuleSuggestions(toolkit,(input,schema)->{fail("Mixed uses must not be proposed");return "";});
    assertTrue(proposer.suggest(true).contains("候補なし"));
    Files.writeString(toolkit.rules().path(),"""
      entertainmentRules:
        - id: explicit-jvm
          priority: 300
          match: {serviceRegex: YouTube, titleRegex: JVM}
          classify: {entertainmentDisposition: NON_ENTERTAINMENT, confidence: 0.9}
      """);
    assertTrue(toolkit.rules().reload());assertTrue(proposer.suggest(true).contains("候補なし"));
  }
  @Test void modelRequestIsStructuredAndHasNoTools() throws Exception {
    var model=org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
    org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(proposal("Hugging Face","research"))))));
    new LlmClassificationRuleModel(()->model,()->org.springframework.ai.openai.OpenAiChatOptions.builder().build()).propose("untrusted metadata",ClassificationRuleSuggestions.SCHEMA);
    var argument=org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);org.mockito.Mockito.verify(model).call(argument.capture());
    var options=(org.springframework.ai.openai.OpenAiChatOptions)argument.getValue().getOptions();
    assertNotNull(options.getResponseFormat());assertFalse(options.getInternalToolExecutionEnabled());assertEquals(8192,options.getMaxCompletionTokens());
  }
  @Test void configuredOutputBudgetIsBoundAndSentToModel() {
    var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
        Map.of("rei.activity.classification.rule-suggestion.max-output-tokens","16384"));
    var properties=new org.springframework.boot.context.properties.bind.Binder(source)
        .bind("rei.activity",org.springframework.boot.context.properties.bind.Bindable.of(ActivityProperties.class)).get();
    properties.validate();
    var model=org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
    var base=org.springframework.ai.openai.OpenAiChatOptions.builder().maxTokens(2048).build();
    var proposer=new LlmClassificationRuleModel(()->model,()->base,properties.getClassification().getRuleSuggestion().getMaxOutputTokens());
    var metadata=org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("length").build();
    org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(
        new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("partial JSON"),metadata))));
    String error=assertThrows(IllegalArgumentException.class,()->proposer.propose("sample",ClassificationRuleSuggestions.SCHEMA)).getMessage();
    assertTrue(error.contains("16384"));assertTrue(error.contains("rei.activity.classification.rule-suggestion.max-output-tokens"));
    var argument=org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
    org.mockito.Mockito.verify(model).call(argument.capture());
    var actual=(org.springframework.ai.openai.OpenAiChatOptions)argument.getValue().getOptions();
    assertEquals(16384,actual.getMaxCompletionTokens());assertNull(actual.getMaxTokens());assertEquals(2048,base.getMaxTokens());
    org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(
        new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(proposal("Hugging Face","research"))))));
    assertEquals(proposal("Hugging Face","research"),proposer.propose("sample",ClassificationRuleSuggestions.SCHEMA));
  }
  @Test void outputBudgetMustBePositive() {
    var properties=new ActivityProperties();
    assertEquals(8192,properties.getClassification().getRuleSuggestion().getMaxOutputTokens());
    for(int invalid:new int[]{0,-1}) {
      properties.getClassification().getRuleSuggestion().setMaxOutputTokens(invalid);
      assertThrows(IllegalArgumentException.class,properties::validate);
      assertThrows(IllegalArgumentException.class,()->new LlmClassificationRuleModel(()->null,()->null,invalid));
    }
  }
  @Test void classificationSchemaRejectsFieldsThatCompilerCannotAccept() throws Exception {
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    var schema=ClassificationRuleSuggestions.schema(false);
    var validator=com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
        .getSchema(tools.jackson.databind.json.JsonMapper.builder().build().readTree(schema));
    var json=tools.jackson.databind.json.JsonMapper.builder().build();
    assertTrue(validator.validate(json.readTree(proposal("Hugging Face","research"))).isEmpty());
    for(String pointer:List.of("/match/serviceRegex","/match/contentRegex","/classify/entertainmentDisposition","/classify/confidence")) {
      var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(proposal("Hugging Face","research"));
      int split=pointer.lastIndexOf('/');
      ((com.fasterxml.jackson.databind.node.ObjectNode)node.at(pointer.substring(0,split))).put(pointer.substring(split+1),"unexpected");
      assertFalse(validator.validate(json.readTree(node.toString())).isEmpty(),pointer);
    }
    assertFalse(validator.validate(json.readTree(proposal("Hugging Face","research").replace("\"processRegex\":\"firefox\"","\"processRegex\":null"))).isEmpty());
    assertFalse(validator.validate(json.readTree(proposal("Hugging Face","research").replace("CLASSIFICATION","ENTERTAINMENT"))).isEmpty());
  }
  @Test void compilerFailureExplainsExactConstraint() {
    var proposer=new ClassificationRuleSuggestions(toolkit("constraints.db"),(input,schema)->"");
    var cases=Map.of(
        proposal("Hugging Face","research").replace("\"serviceRegex\":null","\"serviceRegex\":\"Hugging Face\""),"CLASSIFICATION_MATCH_FIELDS",
        proposal("Hugging Face","research").replace("\"confidence\":null","\"confidence\":0.9"),"CLASSIFICATION_FIELDS",
        proposal("(Hugging)+","research"),"REGEX_GROUP_REPETITION",
        proposal("H.*u.*g.*","research"),"REGEX_QUANTIFIER_LIMIT",
        proposal(".*","research"),"BROAD_REGEX: match.titleRegex",
        proposal("Hugging Face","research").replace("\"titleRegex\":\"Hugging Face\"","\"titleRegex\":null"),"MISSING_CONTEXT",
        proposal("Hugging Face","research").replace("\"processRegex\":\"firefox\"","\"processRegex\":null"),"MISSING_SCOPE");
    for(var entry:cases.entrySet()) {
      String message=assertThrows(IllegalArgumentException.class,()->proposer.validate(entry.getKey(),false)).getMessage();
      assertTrue(message.contains(entry.getValue()),message);
      assertFalse(message.contains("Hugging"),message);
    }
  }
  private ClassificationToolkit toolkit(String name) {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve(name));
    return new ClassificationToolkit(new ActivityProperties(),new OperationalRules(dir.resolve("rules.yaml")),new ClassificationTelemetryRepository(ds),Clock.fixed(ActivityEvidenceClassifierTest.NOW,ZoneOffset.UTC));
  }
  @Test void failuresIdentifyStageAndReasonWithoutApplyingRules() throws Exception {
    var toolkit=toolkit("diagnostics.db");
    for(int i=0;i<3;i++){toolkit.observe(toolkit.decorate(OperationalTelemetryTest.record("r"+i,"firefox","Hugging Face","unknown",false)));toolkit.observe(toolkit.decorate(OperationalTelemetryTest.record("r"+i,"firefox","Hugging Face","research",true)));}
    var before=toolkit.rules().snapshot();
    var cases=new LinkedHashMap<String,String>();
    cases.put("", "EMPTY_RESPONSE");
    cases.put("not JSON secret-payload", "INVALID_JSON");
    cases.put("{}", "SCHEMA_VALIDATION_FAILED");
    cases.put("x".repeat(16385), "RESPONSE_TOO_LARGE");
    cases.put(proposal("Hugging Face","research").replace("CLASSIFICATION","ENTERTAINMENT"), "WRONG_RULE_TYPE");
    cases.put(proposal("[","research"), "INVALID_REGEX");
    cases.put(proposal(".*","research"), "INVALID_RULE_DEFINITION");
    cases.put(proposal("Hugging Face","research").replace("0.85","0.5"), "LOW_PROPOSAL_CONFIDENCE");
    cases.put(proposal("Hugging Face","research").replace("huggingface-user","EDITOR_TITLE"), "EXISTING_RULE_ID");
    cases.put(proposal("Unrelated","research"), "SAMPLE_MISMATCH");
    cases.put(proposal("Hugging Face","development"), "EVIDENCE_CONFLICT");
    for(var entry:cases.entrySet()) {
      String result=new ClassificationRuleSuggestions(toolkit,(input,schema)->entry.getKey()).suggest(false);
      assertTrue(result.contains(entry.getValue()),result);
      assertTrue(result.contains("有効ルールは変更していません"));
      assertFalse(result.contains("secret-payload"));
      assertSame(before,toolkit.rules().snapshot());
    }
    String result=new ClassificationRuleSuggestions(toolkit,(input,schema)->{
      throw new IllegalStateException("secret-payload",new java.net.SocketTimeoutException("private-title"));
    }).suggest(false);
    assertTrue(result.contains("失敗段階: LLM呼び出し"));
    assertTrue(result.contains("IllegalStateException -> SocketTimeoutException"));
    assertFalse(result.contains("secret-payload"));assertFalse(result.contains("private-title"));
    String httpResult=new ClassificationRuleSuggestions(toolkit,(input,schema)->{
      throw org.springframework.web.client.HttpClientErrorException.create(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
          "private-status",org.springframework.http.HttpHeaders.EMPTY,"secret-body".getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.charset.StandardCharsets.UTF_8);
    }).suggest(false);
    assertTrue(httpResult.contains("HTTP 429"),httpResult);
    assertFalse(httpResult.contains("private-status"));assertFalse(httpResult.contains("secret-body"));
    assertFalse(Files.exists(toolkit.rules().path()));
  }
  @Test void duplicateMatchHasDistinctDiagnostic() throws Exception {
    var toolkit=toolkit("duplicate.db");
    Files.writeString(toolkit.rules().path(),"""
      classificationRules:
        - id: another-id
          match: {processRegex: firefox, titleRegex: Hugging Face}
          classify: {category: research}
      """);
    assertTrue(toolkit.rules().reload());
    var proposer=new ClassificationRuleSuggestions(toolkit,(input,schema)->"");
    assertTrue(assertThrows(IllegalArgumentException.class,()->proposer.validate(proposal("Hugging Face","research"),false)).getMessage().contains("EXISTING_RULE_MATCH"));
  }
  @Test void behaviorSchemaFailureIdentifiesFieldWithoutEchoingValue() throws Exception {
    var toolkit=toolkit("behavior-diagnostics.db");
    for(int i=0;i<3;i++)toolkit.observe(toolkit.decorate(video("v"+i,"JVM compilation deep dive","media")));
    String result=new ClassificationRuleSuggestions(toolkit,(input,schema)->proposal("JVM","private-category")).suggest(true);
    assertTrue(result.contains("失敗段階: 応答検証"),result);
    assertTrue(result.contains("SCHEMA_VALIDATION_FAILED"),result);
    assertTrue(result.contains("/properties/classify/properties/category/enum"),result);
    assertFalse(result.contains("private-category"));
  }
  @Test void modelReportsOutputLimitAndMissingResponse() {
    var model=org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
    var proposer=new LlmClassificationRuleModel(()->model,()->org.springframework.ai.openai.OpenAiChatOptions.builder().build());
    assertTrue(assertThrows(IllegalArgumentException.class,()->proposer.propose("sample",ClassificationRuleSuggestions.SCHEMA)).getMessage().contains("EMPTY_RESPONSE"));
    var metadata=org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("length").build();
    org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(
        new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("partial JSON"),metadata))));
    assertTrue(assertThrows(IllegalArgumentException.class,()->proposer.propose("sample",ClassificationRuleSuggestions.SCHEMA)).getMessage().contains("OUTPUT_LIMIT"));
  }
  static ActivityRecord video(String id,String title,String category) {
    var r=OperationalTelemetryTest.record(id,"firefox",title,category,false);
    return new ActivityRecord(r.id(),r.capturedAt(),r.durationEstimate(),r.observations(),r.foreground(),new ActivityRecord.Inference("",List.of(new ActivityRecord.Activity("foreground",category,"firefox","YouTube",title,""))),.9,List.of(),0,false,"test",r.detection());
  }
  static String proposal(String regex,String category){return """
    {"ruleType":"CLASSIFICATION","id":"huggingface-user","priority":200,
     "match":{"processRegex":"firefox","titleRegex":"%s","serviceRegex":null,"contentRegex":null},
     "classify":{"category":"%s","service":"Hugging Face","categoryConfidence":0.9,"serviceConfidence":0.95,"entertainmentDisposition":null,"confidence":null},
     "proposalConfidence":0.85,"rationale":"Three consistent samples"}
    """.formatted(regex,category);}
}
