package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemporalActivityInferenceTest {
  static final Instant AT=Instant.parse("2026-10-10T14:00:00Z");
  static ActivityRecord record(String id,Instant at,String content) {
    var fg=new ForegroundWindow("IDE",1,"rei tests","window");
    var evidence=new ActivityEvidence(at,fg,List.of(),"rei","p",List.of(),null,
        new ActivityEvidence.WorkReference("p",at,2,at,new ActivityEvidence.GitReference("main","commit",at),List.of(),false));
    return new ActivityRecord(id,at,15,List.of(),fg,new ActivityRecord.Inference("推定",List.of(new ActivityRecord.Activity("foreground","coding","IDE","",content,"rei"))),.8,List.of(),0,false,"continuity",
        new ActivityRecord.Detection(evidence,List.of("WINDOW_TITLE"),false,"EVIDENCE_ONLY","FINAL",Map.of(),""));
  }
  @Test void aggregatesChronologicallyAndKeepsAllIdsAndAgentOwnership() {
    var event=new WorkActivityInference.Execution("event",AT,"p","SHELL","REI","COMPLETED","", "session","turn","run");
    var input=TemporalActivityEvidence.build(List.of(record("b",AT.plusSeconds(15),"テスト修正"),record("a",AT,"テスト修正")),List.of(event),AT.minusSeconds(300),AT.plusSeconds(30));
    assertEquals(1,input.groups().size());assertEquals(List.of("a","b"),input.observationIds());
    assertEquals("REI",input.executions().getFirst().actor());assertEquals("p",input.groups().getFirst().projectId());
    assertTrue(input.ruleInference().inferredActivity().contains("テスト修正"));
    assertTrue(input.ruleInference().inferredActivity().contains("可能性"));
  }
  @Test void oneObservationOrConflictingProjectsDoesNotClaimSpecificWork() {
    var input=TemporalActivityEvidence.build(List.of(record("a",AT,"テスト修正")),List.of(),AT.minusSeconds(300),AT);
    assertEquals("UNKNOWN",input.ruleInference().status());assertEquals(0,input.ruleInference().confidence());
  }
  @Test void repeatedObservationsHaveSameSemanticFingerprintButLateEnrichmentChangesIt() {
    var a=record("a",AT,"テスト修正");var b=record("b",AT.plusSeconds(15),"テスト修正");
    var one=TemporalActivityEvidence.build(List.of(a),List.of(),AT.minusSeconds(300),AT);
    var two=TemporalActivityEvidence.build(List.of(a,b),List.of(),AT.minusSeconds(300),AT.plusSeconds(15));
    // The second observation changes insufficient evidence into sufficient evidence; subsequent repeats do not.
    var three=TemporalActivityEvidence.build(List.of(a,b,record("c",AT.plusSeconds(30),"テスト修正")),List.of(),AT.minusSeconds(300),AT.plusSeconds(30));
    assertNotEquals(one.fingerprint(),two.fingerprint());assertEquals(two.fingerprint(),three.fingerprint());
    assertNotEquals(two.fingerprint(),TemporalActivityEvidence.build(List.of(a,record("b",AT.plusSeconds(15),"ビルド確認")),List.of(),AT.minusSeconds(300),AT.plusSeconds(15)).fingerprint());
  }
  @Test void boundedInputRejectsOversizedEvidenceInsteadOfSendingFullHistory() {
    var input=TemporalActivityEvidence.build(List.of(record("a",AT,"x".repeat(10000)),record("b",AT.plusSeconds(15),"x".repeat(10000))),List.of(),AT.minusSeconds(300),AT.plusSeconds(15));
    assertTrue(input.structuredInput(4000).length()<=4000);
    assertThrows(IllegalArgumentException.class,()->input.structuredInput(10));
  }
  @Test void serviceLimitsFrequencySkipsSameMeaningAndReevaluatesLateResults() {
    var p=new ActivityProperties();p.setEnabled(true);var time=new ActivityChangeScopeTest.Time();time.now=AT;
    var store=mock(ActivityStore.class);var supplements=mock(WorkActivityInferenceStore.class);
    when(store.findObservationsBetweenBounded(any(),any(),anyInt())).thenReturn(List.of(record("a",AT,"テスト修正"),record("b",AT,"テスト修正")));
    var service=new TemporalActivityInferenceService(p,store,supplements,null,time,()->List.of(),()->false);
    service.tick();service.tick();verify(supplements).save(any());
    time.advance(60);service.tick();verify(supplements).save(any());
    when(store.findObservationsBetweenBounded(any(),any(),anyInt())).thenReturn(List.of(record("a",AT,"ビルド確認"),record("b",AT,"ビルド確認")));
    time.advance(60);service.tick();verify(supplements,times(2)).save(any());
    assertEquals(1,service.metrics().skipped());assertEquals(2,service.metrics().completed());
  }
  @Test void failureRetriesAreBoundedAndDoNotRewriteBaseActivity() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getTemporal().setLlmEnabled(true);
    var time=new ActivityChangeScopeTest.Time();time.now=AT;var store=mock(ActivityStore.class);var supplements=mock(WorkActivityInferenceStore.class);
    when(store.findObservationsBetweenBounded(any(),any(),anyInt())).thenReturn(List.of(record("a",AT,"テスト修正"),record("b",AT,"テスト修正")));
    var model=mock(TemporalActivityInferenceService.Model.class);when(model.infer(any(),anyInt(),anyInt(),anyLong())).thenThrow(new java.io.IOException());
    var service=new TemporalActivityInferenceService(p,store,supplements,model,time,()->List.of(),()->false);
    service.tick();time.advance(60);service.tick();time.advance(60);service.tick();
    verify(model,times(2)).infer(any(),anyInt(),anyInt(),anyLong());verify(supplements,times(2)).save(argThat(r->r.method().equals("LLM_FAILED_RULES")));
    verify(store,never()).append(any());verify(store,never()).replace(any());assertEquals(2,service.metrics().failed());
  }
  @Test void sqliteSupplementsAndMidnightQueriesNeverAddObservedSeconds(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
    var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("activity.db"));
    var raw=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    var before=Instant.parse("2026-10-10T23:59:45Z");var after=before.plusSeconds(30);
    raw.append(record("a",before,"テスト修正"));raw.append(record("b",after,"テスト修正"));
    var storage=new WorkActivityInferenceStore(ds);var inference=TemporalActivityEvidence.build(raw.findObservationsBetweenBounded(before.minusSeconds(1),after.plusSeconds(1),10),List.of(),before.minusSeconds(300),after).ruleInference();
    storage.save(inference);storage.save(inference);
    assertEquals(0,storage.findBetween(before.minusSeconds(30),Instant.parse("2026-10-11T00:00:00Z"),10).size());
    assertEquals(List.of(inference),storage.findBetween(Instant.parse("2026-10-11T00:00:00Z"),after.plusSeconds(1),10));
    assertEquals(30,raw.findBetween(before,after.plusSeconds(15)).stream().mapToLong(ActivitySession::observedSeconds).sum());
  }
  @Test void backgroundCandidateMustNotBecomeSpecificForegroundWork() {
    var base=record("a",AT,"テスト修正");
    var background=new ActivityRecord.Activity("background","coding","OtherApp","","秘密の別作業","rei");
    var altered=new ActivityRecord(base.id(),base.capturedAt(),15,List.of(),base.foreground(),new ActivityRecord.Inference("",List.of(background,base.inference().activities().getFirst())),.8,List.of(),0,false,base.continuityId(),base.detection());
    var second=new ActivityRecord("b",AT.plusSeconds(15),15,List.of(),altered.foreground(),altered.inference(),.8,List.of(),0,false,altered.continuityId(),altered.detection());
    var inferred=TemporalActivityEvidence.build(List.of(altered,second),List.of(),AT.minusSeconds(300),AT.plusSeconds(15)).ruleInference();
    assertTrue(inferred.inferredActivity().contains("テスト修正"));assertFalse(inferred.inferredActivity().contains("秘密の別作業"));
  }
  @Test void knownUsageOfRejectedCompletionIsCountedAndStopsInvalidRetries() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getTemporal().setLlmEnabled(true);var time=new ActivityChangeScopeTest.Time();time.now=AT;
    var store=mock(ActivityStore.class);var supplements=mock(WorkActivityInferenceStore.class);var model=mock(TemporalActivityInferenceService.Model.class);
    when(store.findObservationsBetweenBounded(any(),any(),anyInt())).thenReturn(List.of(record("a",AT,"テスト修正"),record("b",AT,"テスト修正")));
    when(model.infer(any(),anyInt(),anyInt(),anyLong())).thenThrow(new TemporalActivityInferenceService.ModelFailure(100,false,new IllegalArgumentException()));
    var service=new TemporalActivityInferenceService(p,store,supplements,model,time,()->List.of(),()->false);
    service.tick();time.advance(60);service.tick();verify(model).infer(any(),anyInt(),anyInt(),anyLong());assertEquals(100,service.metrics().totalTokens());
    verify(supplements).save(argThat(r->r.totalTokens()==100 && r.llmCalls()==1));
  }
  @Test void retryUsesOriginalSnapshotAndIdsEvenWhenEquivalentObservationsArrive() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getTemporal().setLlmEnabled(true);var time=new ActivityChangeScopeTest.Time();time.now=AT;
    var store=mock(ActivityStore.class);var supplements=mock(WorkActivityInferenceStore.class);var model=mock(TemporalActivityInferenceService.Model.class);
    var a=record("a",AT,"テスト修正");var b=record("b",AT.plusSeconds(15),"テスト修正");time.advance(15);
    when(store.findObservationsBetweenBounded(any(),any(),anyInt())).thenReturn(List.of(a,b));
    when(model.infer(any(),anyInt(),anyInt(),anyLong())).thenThrow(new java.io.IOException()).thenReturn(new TemporalActivityInferenceService.Outcome("テスト修正の可能性があります",.6,100));
    var service=new TemporalActivityInferenceService(p,store,supplements,model,time,()->List.of(),()->false);service.tick();
    when(store.findObservationsBetweenBounded(any(),any(),anyInt())).thenReturn(List.of(a,b,record("c",AT.plusSeconds(30),"テスト修正")));time.advance(60);service.tick();
    var input=org.mockito.ArgumentCaptor.forClass(String.class);verify(model,times(2)).infer(input.capture(),anyInt(),anyInt(),anyLong());assertEquals(input.getAllValues().getFirst(),input.getValue());
    var saved=org.mockito.ArgumentCaptor.forClass(WorkActivityInference.class);verify(supplements,times(2)).save(saved.capture());
    assertEquals(List.of("a","b"),saved.getValue().observationIds());assertEquals(saved.getAllValues().getFirst().id(),saved.getValue().id());assertEquals(time.instant(),saved.getValue().inferredAt());
  }
  @Test void storageFailureRetriesSaveWithoutPayingForTheModelAgain() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getTemporal().setLlmEnabled(true);var time=new ActivityChangeScopeTest.Time();time.now=AT;
    var store=mock(ActivityStore.class);var supplements=mock(WorkActivityInferenceStore.class);var model=mock(TemporalActivityInferenceService.Model.class);
    when(store.findObservationsBetweenBounded(any(),any(),anyInt())).thenReturn(List.of(record("a",AT,"テスト修正"),record("b",AT,"テスト修正")));
    when(model.infer(any(),anyInt(),anyInt(),anyLong())).thenReturn(new TemporalActivityInferenceService.Outcome("テスト修正の可能性があります",.6,100));
    doThrow(new IllegalStateException()).doNothing().when(supplements).save(any());
    var service=new TemporalActivityInferenceService(p,store,supplements,model,time,()->List.of(),()->false);service.tick();time.advance(60);service.tick();
    verify(model).infer(any(),anyInt(),anyInt(),anyLong());verify(supplements,times(2)).save(any());
  }
  @Test void unchangedFiveMinuteInputMakesOneModelCallInsteadOfSix() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);p.getTemporal().setLlmEnabled(true);var time=new ActivityChangeScopeTest.Time();time.now=AT;
    var store=mock(ActivityStore.class);var supplements=mock(WorkActivityInferenceStore.class);var model=mock(TemporalActivityInferenceService.Model.class);
    var records=new ArrayList<ActivityRecord>();records.add(record("a",AT,"テスト修正"));records.add(record("b",AT,"テスト修正"));
    when(store.findObservationsBetweenBounded(any(),any(),anyInt())).thenAnswer(i->List.copyOf(records));
    when(model.infer(any(),anyInt(),anyInt(),anyLong())).thenReturn(new TemporalActivityInferenceService.Outcome("テスト修正の可能性があります",.6,100));
    var service=new TemporalActivityInferenceService(p,store,supplements,model,time,()->List.of(),()->false);
    for(int minute=0;minute<=5;minute++){if(minute>0)for(int second=15;second<=60;second+=15)records.add(record("r"+minute+"-"+second,AT.plusSeconds((minute-1)*60+second),"テスト修正"));service.tick();time.advance(60);}
    verify(model).infer(any(),anyInt(),anyInt(),anyLong());assertEquals(5,service.metrics().skipped());assertEquals(100,service.metrics().totalTokens());
    System.out.printf("Activity temporal synthetic comparison: checks=6 observations=%d naive_calls=6 optimized_calls=%d mock_tokens=%d%n",records.size(),service.metrics().llmCalls(),service.metrics().totalTokens());
  }
  @Test void timelineSupplementIsSeparateAndItsFailureKeepsBaseSummary() {
    var records=mock(ActivityStore.class);var supplements=mock(WorkActivityInferenceStore.class);
    var inference=TemporalActivityEvidence.build(List.of(record("a",AT,"テスト修正"),record("b",AT.plusSeconds(15),"テスト修正")),List.of(),AT.minusSeconds(300),AT.plusSeconds(15)).ruleInference();
    when(supplements.findBetween(any(),any(),anyInt())).thenReturn(List.of(inference));
    var timeline=new ActivityTimeline(records,Clock.fixed(AT.plusSeconds(30),ZoneOffset.UTC),new ActivityTimeline(records,Clock.systemUTC()).summaryPolicy(),DailySummaryService.local(),null,supplements);
    var presentation=new ActivityTimelinePresentationService(timeline,null);assertInstanceOf(ActivityTimelineEntry.WorkInferenceEntry.class,presentation.entries("today",true).getFirst());
    assertTrue(presentation.format("today",true).contains("observationIds: [a, b]"));assertTrue(timeline.summary("today").contains("時間・スコアには加算しません"));
    assertEquals(1,new ActivityTools(timeline).activityWorkInferences("today","p").size());assertEquals(0,new ActivityTools(timeline).activityWorkInferences("today","other").size());
    when(supplements.findBetween(any(),any(),anyInt())).thenThrow(new IllegalStateException());assertDoesNotThrow(()->timeline.summary("today"));
    verify(records,never()).append(any());verify(records,never()).replace(any());
  }
  @Test void exactRecentQueryIsBoundedWithoutScanningEarlierSameDay(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
    var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("recent.db"));
    var raw=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    raw.append(record("old",AT.minusSeconds(3600),"旧観測"));raw.append(record("a",AT,"テスト修正"));raw.append(record("b",AT.plusSeconds(15),"テスト修正"));
    assertEquals(List.of("a","b"),raw.findObservationsBetweenBounded(AT.minusSeconds(300),AT.plusSeconds(30),2).stream().map(ActivityRecord::id).toList());
    assertThrows(ActivityQueryLimitException.class,()->raw.findObservationsBetweenBounded(AT.minusSeconds(300),AT.plusSeconds(30),1));
  }
  @Test void pausedDisabledAndRestartedSameEvidenceDoesNotInvokeModel() {
    var p=new ActivityProperties();p.setEnabled(true);var store=mock(ActivityStore.class);var supplements=mock(WorkActivityInferenceStore.class);var time=new ActivityChangeScopeTest.Time();time.now=AT;
    var service=new TemporalActivityInferenceService(p,store,supplements,null,time,()->List.of(),()->true);service.tick();verifyNoInteractions(store,supplements);
    p.getTemporal().setEnabled(false);service=new TemporalActivityInferenceService(p,store,supplements,null,time,()->List.of(),()->false);service.tick();verifyNoInteractions(store,supplements);
    p.getTemporal().setEnabled(true);var list=List.of(record("a",AT,"テスト修正"),record("b",AT,"テスト修正"));
    when(store.findObservationsBetweenBounded(any(),any(),anyInt())).thenReturn(list);
    when(supplements.latest()).thenReturn(Optional.of(TemporalActivityEvidence.build(list,List.of(),AT.minusSeconds(300),AT).ruleInference()));
    service=new TemporalActivityInferenceService(p,store,supplements,null,time,()->List.of(),()->false);service.tick();verify(supplements,never()).save(any());
  }
  @Test void temporalExecutionsRetainFiveMinuteFailuresAndNeverBecomeUserActions() {
    var source=new ActivityAgentEvidenceSource(null);
    source.onEvent(new dev.mikoto2000.rei.event.AgentEvent("failure",0,AT,dev.mikoto2000.rei.event.AgentEventType.TOOL_FAILED,1,"session","turn","run",null,null,
        new dev.mikoto2000.rei.event.ToolFailedPayload("call","runCommand",null),"p"));
    assertEquals("REI",source.executions(AT.plusSeconds(299),300).getFirst().actor());assertEquals("FAILED",source.executions(AT,300).getFirst().outcome());
    assertTrue(source.collect(AT).events().isEmpty());assertTrue(source.executions(AT.plusSeconds(301),300).isEmpty());
    assertThrows(IllegalArgumentException.class,()->new WorkActivityInference("id","hash",AT,AT,AT,"","","",List.of(),List.of(
        new WorkActivityInference.Execution("event",AT,"p","SHELL","USER","COMPLETED","","s","t","r")),0,"UNKNOWN","RULES",0,0));
  }
  @Test void conflictingProjectEvidenceStaysUnknownAndEmptyWindowClearsCachedInference() {
    var a=record("a",AT,"テスト修正");var b=record("b",AT,"テスト修正");var d=b.detection();var e=d.evidence();
    var other=new ActivityEvidence(e.capturedAt(),e.foreground(),e.visibleWindows(),"other","other",e.events(),e.history());
    b=new ActivityRecord(b.id(),b.capturedAt(),b.durationEstimate(),b.observations(),b.foreground(),b.inference(),b.confidence(),b.screenshotReferences(),0,false,b.continuityId(),
        new ActivityRecord.Detection(other,d.classificationSources(),false,d.classificationMode(),d.status(),d.sourceConfidence(),d.reason()));
    assertEquals("UNKNOWN",TemporalActivityEvidence.build(List.of(a,b),List.of(),AT.minusSeconds(300),AT).ruleInference().status());
    var p=new ActivityProperties();p.setEnabled(true);var time=new ActivityChangeScopeTest.Time();time.now=AT;var store=mock(ActivityStore.class);var supplements=mock(WorkActivityInferenceStore.class);
    when(store.findObservationsBetweenBounded(any(),any(),anyInt())).thenReturn(List.of(a,record("b",AT,"テスト修正")),List.of(),List.of(a,record("b",AT,"テスト修正")));
    var service=new TemporalActivityInferenceService(p,store,supplements,null,time,()->List.of(),()->false);
    service.tick();time.advance(60);service.tick();time.advance(60);service.tick();verify(supplements,times(2)).save(any());
  }
}
