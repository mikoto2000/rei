package dev.mikoto2000.rei.activity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationalRulesTest {
  @TempDir Path dir;
  static final String RULE="""
      classificationRules:
        - id: chat-research
          priority: 300
          match:
            processRegex: '(?i)firefox'
            titleRegex: 'ChatGPT'
          classify:
            category: research
            service: ChatGPT
            categoryConfidence: 0.9
      entertainmentRules:
        - id: youtube-tech-custom
          priority: 200
          match:
            serviceRegex: YouTube
            titleRegex: '(?i)Spring|tutorial'
          classify:
            entertainmentDisposition: NON_ENTERTAINMENT
            confidence: 0.9
      """;
  @Test void userOverridesBuiltInAndEntertainmentIsIndependent() throws Exception {
    var f=dir.resolve("rules.yaml");Files.writeString(f,RULE);var rules=new OperationalRules(f);
    var c=rules.classify(ActivityEvidenceClassifierTest.evidence("firefox","ChatGPT"));
    assertEquals("research",c.inference().activities().getFirst().type());
    assertEquals("chat-research",c.reason());
    assertEquals(EntertainmentDisposition.NON_ENTERTAINMENT,rules.entertainment("YouTube","firefox","Spring tutorial","","media").disposition());
    assertEquals(EntertainmentDisposition.UNCERTAIN,rules.entertainment("YouTube","firefox","YouTube","","media").disposition());
  }
  @Test void failedReloadRetainsWholePreviousSnapshot() throws Exception {
    var f=dir.resolve("rules.yaml");Files.writeString(f,RULE);var rules=new OperationalRules(f);var before=rules.snapshot();
    for(String bad:new String[]{RULE.replace("ChatGPT'","['"),RULE.replace("research","invented"),RULE.replace("0.9","1.5"),RULE.replace("300","1.5"),RULE.replace("NON_ENTERTAINMENT","INVALID"),RULE.replace("youtube-tech-custom","chat-research"),"classificationRules: [{id: blank, match: {}, classify: {category: media}}]"}) {
      Files.writeString(f,bad);assertFalse(rules.reload());assertSame(before,rules.snapshot());
    }
    Files.writeString(f,RULE.replace("research","documentation"));assertTrue(rules.reload());assertNotSame(before,rules.snapshot());
  }
  @Test void missingFileIsValidAndBroadProposalIsRejected() throws Exception {
    var rules=new OperationalRules(dir.resolve("absent"));assertFalse(rules.snapshot().rules().isEmpty());
    assertThrows(IllegalArgumentException.class,()->OperationalRules.compile("entertainmentRules: [{id: broad, match: {titleRegex: '.*'}, classify: {entertainmentDisposition: ENTERTAINMENT, confidence: 0.9}}]",true));
    assertEquals(4,OperationalRules.compile(Files.readString(Path.of("docs/classification-rules.example.yaml")),false).size());
  }
  @Test void ruleDefinitionsAreStrictAndDangerousRegexIsRejected() {
    for(String bad:new String[]{RULE.replace("chat-research",""),RULE.replace("priority: 300","priority: -1"),RULE.replace("categoryConfidence: 0.9","categoryConfidence: .nan"),RULE.replace("processRegex:","typoRegex:"),RULE.replace("'ChatGPT'","'(a|aa)+'"),RULE.replace("category: research","category: unknown\n      categoryConfidence: 0.9")})
      assertThrows(Exception.class,()->OperationalRules.compile(bad,false),bad);
  }
  @Test void priorityTieIsDeterministicAndConflictingClassificationIsUnusable() throws Exception {
    var f=dir.resolve("tie.yaml");Files.writeString(f,"""
      classificationRules:
        - {id: z-rule, priority: 400, match: {processRegex: firefox, titleRegex: ChatGPT}, classify: {category: social, categoryConfidence: 0.9}}
        - {id: a-rule, priority: 400, match: {processRegex: firefox, titleRegex: ChatGPT}, classify: {category: research, categoryConfidence: 0.9}}
      """);
    var c=new OperationalRules(f).classify(ActivityEvidenceClassifierTest.evidence("firefox","ChatGPT"));
    assertTrue(c.reason().startsWith("a-rule"));assertFalse(c.usable(.8));assertTrue(c.reason().contains("CONFLICTING_RULES"));
    Files.writeString(f,"classificationRules: [{id: x-alt, priority: 100, match: {processRegex: firefox, titleRegex: X}, classify: {category: research, categoryConfidence: 0.9}}]");
    var tied=new OperationalRules(f).classify(ActivityEvidenceClassifierTest.evidence("firefox","X"));
    assertFalse(tied.usable(.8));assertTrue(tied.reason().contains("CONFLICTING_RULES"));
  }
  @Test void unknownCannotClaimUsableConfidence() {
    assertThrows(IllegalArgumentException.class,()->OperationalRules.compile("classificationRules: [{id: impossible, match: {titleRegex: abc}, classify: {category: unknown, categoryConfidence: 0.9}}]",false));
  }
  @Test void entertainmentOverrideAndConflictsAreConservative() throws Exception {
    var f=dir.resolve("ent.yaml");Files.writeString(f,"""
      entertainmentRules:
        - {id: my-tech, priority: 400, match: {serviceRegex: YouTube, titleRegex: Spring}, classify: {entertainmentDisposition: ENTERTAINMENT, confidence: 0.9}}
      """);var rules=new OperationalRules(f);
    assertEquals(EntertainmentDisposition.ENTERTAINMENT,rules.entertainment("YouTube","firefox","Spring tutorial","","media").disposition());
    Files.writeString(f,Files.readString(f)+"  - {id: my-conflict, priority: 400, match: {serviceRegex: YouTube, titleRegex: Spring}, classify: {entertainmentDisposition: NON_ENTERTAINMENT, confidence: 0.9}}\n");assertTrue(rules.reload());
    assertEquals("CONFLICTING_ENTERTAINMENT_RULES",rules.entertainment("YouTube","firefox","Spring tutorial","","media").reason());
  }
}
