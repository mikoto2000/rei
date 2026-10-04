package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import dev.mikoto2000.rei.workcontext.*;
import dev.mikoto2000.rei.event.*;
import static org.junit.jupiter.api.Assertions.*;

class ActivityWorkContextTest {
  @TempDir Path directory;
  final Instant at=Instant.parse("2026-10-04T09:00:00Z");
  @Test void sourceRetainsOwnedIdentifiersWithoutToolTextAndExpiresThem() {
    var source=new ActivityAgentEvidenceSource(null);
    source.onEvent(new AgentEvent("event",0,at,AgentEventType.TOOL_COMPLETED,1,"session","turn","run",null,null,new ToolCompletedPayload("call","runCommand",1,"secret command result",null,null,null,null),"project"));
    var item=source.collect(at).events().getFirst();
    assertEquals("event",item.eventId());assertEquals("session",item.sessionId());assertEquals("run",item.runId());assertEquals("turn",item.turnId());
    assertFalse(item.toString().contains("secret"));assertEquals(0,source.collect(at.plusSeconds(121)).events().size());
  }
  WorkContext context(long revision,String project) {
    var evidence=new WorkContext.Evidence("event",WorkContext.Origin.TOOL,"session","turn","run","call","private-file",null,at,at,"private-result");
    var item=new WorkContext.Item("item",WorkContext.Kind.VERIFICATION,"private-work","",WorkContext.Status.COMPLETED,List.of(evidence),at,at,false,null);
    return new WorkContext(project,revision,at,at,new WorkContext.GitState("private-directory","main","commit",at.minusSeconds(60)),List.of(item),Set.of("run"));
  }
  ActivityRecord record(ActivityEvidence.RecentEvent event) {
    var base=ActivitySemanticTest.dev(0,"Terminal","X");
    var evidence=new ActivityEvidence(at,base.foreground(),List.of(),"rei","project",List.of(event),null);
    return new ActivityRecord("observation",at,60,List.of(),base.foreground(),base.inference(),.8,List.of(),0,false,"c",new ActivityRecord.Detection(evidence,List.of(),false,"EVIDENCE_ONLY","READY",Map.of(),""));
  }
  @Test void matchesPersistedEventReferencesWithProjectIsolationAndSnapshotSemantics() {
    var store=org.mockito.Mockito.mock(ActivityStore.class);
    var recent=new ActivityEvidence.RecentEvent(at,"project","SHELL","event","session","turn","run");
    org.mockito.Mockito.when(store.findRecordsBetween(org.mockito.Mockito.any(),org.mockito.Mockito.any())).thenReturn(List.of(record(recent),record(recent)));
    var service=new ActivityWorkContextService(store,p->List.of(context(2,"other"),context(1,p)),Clock.fixed(at.plusSeconds(60),ZoneOffset.UTC));
    var links=service.links("project","today");assertEquals(1,links.size());
    assertEquals(1L,links.getFirst().revision());assertEquals(List.of("item"),links.getFirst().itemIds());assertEquals("EVENT_REFERENCE",links.getFirst().basis());
    String text=service.format(links);assertTrue(text.contains("snapshot"));assertFalse(text.contains("private-"));
    assertTrue(service.links("other","today").isEmpty());
  }
  @Test void legacyEvidenceAndMismatchedSessionRemainUnlinked() {
    var store=org.mockito.Mockito.mock(ActivityStore.class);
    var legacy=new ActivityEvidence.RecentEvent(at,"project","SHELL");
    org.mockito.Mockito.when(store.findRecordsBetween(org.mockito.Mockito.any(),org.mockito.Mockito.any())).thenReturn(List.of(record(legacy)));
    var service=new ActivityWorkContextService(store,p->List.of(context(1,p)),Clock.fixed(at,ZoneOffset.UTC));
    assertTrue(service.links("project","today").isEmpty());
    org.mockito.Mockito.when(store.findRecordsBetween(org.mockito.Mockito.any(),org.mockito.Mockito.any())).thenReturn(List.of(record(new ActivityEvidence.RecentEvent(at,"project","SHELL","event","other-session","turn","run"))));
    assertTrue(service.links("project","today").isEmpty());
    assertThrows(DateTimeException.class,()->service.links("project","2026-10-05"));
  }
  @Test void identifiersRoundTripInSQLiteAndOldJsonRemainsReadable() throws Exception {
    var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("activity.db"));
    var store=new SqliteActivityStore(source,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    var recent=new ActivityEvidence.RecentEvent(at,"project","SHELL","event","session","turn","run");store.append(record(recent));
    var restarted=new SqliteActivityStore(source,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    assertEquals(recent,restarted.findRecordsBetween(at,at.plusSeconds(60)).getFirst().detection().evidence().events().getFirst());
    var json=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    var legacy=json.readValue("{\"at\":\"2026-10-04T09:00:00Z\",\"projectId\":\"project\",\"kind\":\"SHELL\"}",ActivityEvidence.RecentEvent.class);
    assertNull(legacy.eventId());
  }
  @Test void shellUsesSelectedProjectAndRequiresSelectionWithoutMutation() {
    var store=org.mockito.Mockito.mock(ActivityStore.class);
    var projects=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var clock=Clock.fixed(at,ZoneOffset.UTC);
    var service=new ActivityWorkContextService(store,p->List.of(),clock);
    var command=new ActivityCommand(new ActivityTimeline(store,clock),null,null);command.workContextLinks(service,projects);
    var output=new java.io.StringWriter();var shell=new picocli.CommandLine(command);shell.setOut(new java.io.PrintWriter(output));shell.setErr(new java.io.PrintWriter(output));
    assertEquals(2,shell.execute("context"));org.mockito.Mockito.verifyNoInteractions(store);
    org.mockito.Mockito.when(projects.currentContext()).thenReturn(new dev.mikoto2000.rei.core.project.ProjectContext(java.util.UUID.randomUUID().toString(),"rei",directory));
    assertEquals(0,shell.execute("context"));assertTrue(output.toString().contains("一致する保存参照なし"));
    org.mockito.Mockito.verify(store).findRecordsBetween(Instant.parse("2026-10-04T00:00:00Z"),at);
    org.mockito.Mockito.verifyNoMoreInteractions(store);
  }
}
