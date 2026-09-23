package dev.mikoto2000.rei.activity;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OperationalWorkflowTest {
  @TempDir Path dir;
  @Test void failedVisionRetainsObservationThenUserRuleSkipsNextVision() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var time=new ActivityChangeScopeTest.Time();
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("flow.db"));
    var rules=new OperationalRules(dir.resolve("rules.yaml"));var toolkit=new ClassificationToolkit(p,rules,new ClassificationTelemetryRepository(ds),time);
    var store=toolkit.wrap(new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC)));
    var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);var screenshots=mock(ScreenshotStore.class);
    when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","ChatGPT"));when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,20));
    when(extractor.extract(any(),any())).thenThrow(new ActivityOutputParser.InvalidOutput(List.of(new ActivityOutputParser.ResultError("/","output_limit"))));
    var capture=new ActivityCapture(p,observer,extractor,store,screenshots,time);capture.useToolkit(toolkit);capture.tick();
    var first=store.findRecordsBetween(time.instant().minusSeconds(1),time.instant().plusSeconds(1)).getFirst();
    assertTrue(first.detection().diagnostics().unknownReasons().contains("VISION_OUTPUT_LIMIT"));assertEquals(1,toolkit.candidates("unknown").size());
    assertEquals(1,toolkit.metrics().get("vision_output_limit"));
    Files.writeString(rules.path(),OperationalRulesTest.RULE);toolkit.poll();time.advance(60);capture.tick();
    var rows=store.findRecordsBetween(time.instant().minusSeconds(61),time.instant().plusSeconds(1));assertEquals(2,rows.size());assertEquals("research",rows.getLast().inference().activities().getFirst().type());
    assertEquals(1,toolkit.metrics().get("hit:chat-research"));assertEquals(1,toolkit.metrics().get("evidence_only"));verify(extractor,times(1)).extract(any(),any());verify(screenshots,never()).save(any(),any(),any());
  }
  @Test void telemetryFailureCannotPreventPrimaryStoreWrite() {
    var ds=mock(javax.sql.DataSource.class);var p=new ActivityProperties();var toolkit=new ClassificationToolkit(p,new OperationalRules(dir.resolve("absent")),new ClassificationTelemetryRepository(ds),Clock.systemUTC());
    var store=mock(ActivityStore.class);var r=OperationalTelemetryTest.record("r","firefox","Hugging Face","unknown",false);
    assertDoesNotThrow(()->toolkit.wrap(store).append(r));verify(store).append(any());
  }
  @Test void compiledSnapshotIsReusedEvenIfFileIsRemovedUntilReload() throws Exception {
    var f=dir.resolve("rules.yaml");Files.writeString(f,OperationalRulesTest.RULE);var rules=new OperationalRules(f);var snapshot=rules.snapshot();
    Files.delete(f);
    for(int i=0;i<100;i++)assertEquals("research",rules.classify(ActivityEvidenceClassifierTest.evidence("firefox","ChatGPT")).inference().activities().getFirst().type());
    assertSame(snapshot,rules.snapshot());rules.poll();assertEquals("unknown",rules.classify(ActivityEvidenceClassifierTest.evidence("firefox","ChatGPT")).inference().activities().getFirst().type());
  }
}
