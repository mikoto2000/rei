package dev.mikoto2000.rei.activity;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OperationalTelemetryTest {
  @TempDir Path dir;
  @Test void registryAggregatesResolvesAndDoesNotCountEnrichmentTwice() throws Exception {
    var p=new ActivityProperties();var rules=new OperationalRules(dir.resolve("rules.yaml"));
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("telemetry.db"));
    var toolkit=new ClassificationToolkit(p,rules,new ClassificationTelemetryRepository(ds),Clock.fixed(ActivityEvidenceClassifierTest.NOW,ZoneOffset.UTC));
    for(int i=0;i<3;i++) {
      var r=record("r"+i,"firefox","Hugging Face", "unknown",false);
      toolkit.observe(toolkit.decorate(r));
      toolkit.observe(toolkit.decorate(record("r"+i,"firefox","Hugging Face","research",true)));
    }
    var entries=toolkit.candidates("unknown");assertEquals(1,entries.size());var e=entries.getFirst();
    assertEquals(3,e.count());assertEquals(0,e.open());assertEquals(3,e.visionSuccess());assertEquals("RESOLVED_BY_VISION",e.state());
    assertEquals(3,toolkit.metrics().get("observations"));
    try(var connection=ds.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM activity_classification_candidates WHERE kind='unknown'")) {
      assertTrue(rows.next());assertEquals(1,rows.getInt(1));
    }
    assertEquals(3,toolkit.metrics().get("vision_fallback"));assertEquals(0,toolkit.metrics().get("evidence_only"));
    assertTrue(toolkit.status().contains("unknown"));
  }
  @Test void hotReloadRetentionAndRuleHitsWorkWithoutModel() throws Exception {
    var p=new ActivityProperties();p.getClassification().getUnknownRegistry().setMaxEntries(1);
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("limit.db"));
    var file=dir.resolve("r.yaml");var rules=new OperationalRules(file);
    var toolkit=new ClassificationToolkit(p,rules,new ClassificationTelemetryRepository(ds),Clock.fixed(ActivityEvidenceClassifierTest.NOW,ZoneOffset.UTC));
    toolkit.observe(toolkit.decorate(record("one","firefox","First page","unknown",false)));
    toolkit.observe(toolkit.decorate(record("two","firefox","Second page","unknown",false)));
    assertEquals(1,toolkit.candidates("unknown").size());
    Files.writeString(file,OperationalRulesTest.RULE);toolkit.poll();
    assertEquals("research",toolkit.classify(ActivityEvidenceClassifierTest.evidence("firefox","ChatGPT")).inference().activities().getFirst().type());
    var video=toolkit.decorate(OperationalSuggestionTest.video("video","Spring tutorial","media"));toolkit.observe(video);toolkit.observe(video);
    assertEquals(1,toolkit.metrics().get("entertainment_user"));assertEquals(1,toolkit.metrics().get("hit:youtube-tech-custom"));
    var future=new ClassificationToolkit(p,rules,new ClassificationTelemetryRepository(ds),Clock.fixed(ActivityEvidenceClassifierTest.NOW.plus(Duration.ofDays(31)),ZoneOffset.UTC));
    assertTrue(future.candidates("unknown").isEmpty());
  }
  @Test void privacyExclusionsApplyBeforeDiagnosticsAndPersistence() {
    var p=new ActivityProperties();var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("privacy.db"));
    var toolkit=new ClassificationToolkit(p,new OperationalRules(dir.resolve("missing")),new ClassificationTelemetryRepository(ds),Clock.systemUTC());
    for(String title:List.of("Private Browsing","token=very-secret","Contact someone@example.com")) {
      var r=toolkit.decorate(record(title,"firefox",title,"unknown",false));toolkit.observe(r);assertNull(r.detection().diagnostics());
    }
    assertTrue(toolkit.candidates("unknown").isEmpty());
  }
  static ActivityRecord record(String id,String process,String title,String category,boolean vision) {
    var evidence=ActivityEvidenceClassifierTest.evidence(process,title);
    var a=new ActivityRecord.Activity("foreground",category,process,category.equals("unknown")?"":"Hugging Face",title,"");
    var d=new ActivityRecord.Detection(evidence,List.of("FOREGROUND_WINDOW"),vision,vision?"EVIDENCE_PLUS_VISION":"EVIDENCE_ONLY",vision?"FINAL":"PROVISIONAL",Map.of(),"GENERIC_BROWSER",new ActivityFieldConfidence(category.equals("unknown")?0:.9,1,.9,0,.8),List.of());
    return new ActivityRecord(id,evidence.capturedAt(),60,List.of(),evidence.foreground(),new ActivityRecord.Inference("",List.of(a)),category.equals("unknown")?0:.9,List.of(),0,false,"test",d);
  }
}
