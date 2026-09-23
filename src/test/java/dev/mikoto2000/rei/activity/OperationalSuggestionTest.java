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
    var proposer=new ClassificationRuleSuggestions(toolkit,(input,schema)->proposal("Hugging Face","research"));
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
    var proposer=new ClassificationRuleSuggestions(toolkit,(input,schema)->output);
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
    assertNotNull(options.getResponseFormat());assertFalse(options.getInternalToolExecutionEnabled());assertEquals(2048,options.getMaxCompletionTokens());
  }
  private ClassificationToolkit toolkit(String name) {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve(name));
    return new ClassificationToolkit(new ActivityProperties(),new OperationalRules(dir.resolve("rules.yaml")),new ClassificationTelemetryRepository(ds),Clock.fixed(ActivityEvidenceClassifierTest.NOW,ZoneOffset.UTC));
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
