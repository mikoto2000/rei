package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.workcontext.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ActivityObservationContextTest {
  @TempDir Path directory;
  final Instant at=Instant.parse("2026-10-05T09:00:00Z");
  final String projectId=UUID.randomUUID().toString();
  ProjectContext project(){return new ProjectContext(projectId,"rei",directory);}
  WorkContext context(String owner,int count) {
    var items=new ArrayList<WorkContext.Item>();
    for(int i=0;i<count;i++) {
      var evidence=new WorkContext.Evidence("event-"+i,WorkContext.Origin.TOOL,"session","turn","run",
          "command-"+i,i==0?"src/Main.java":"../private-file",null,at.minusSeconds(30),at.minusSeconds(20),"private command/result");
      items.add(new WorkContext.Item("task-"+i,WorkContext.Kind.CURRENT_WORK,"private task text","",WorkContext.Status.OPEN,
          List.of(evidence),at.minusSeconds(30),at.minusSeconds(20),false,null));
    }
    return new WorkContext(owner,3,at.minusSeconds(30),at.minusSeconds(20),null,items,Set.of());
  }
  ActivityObservationContextSource source(ActivityProperties properties,WorkContext context) {
    return new ActivityObservationContextSource(properties,this::project,p->Optional.ofNullable(context),
        (root,time)->new WorkContext.GitState(root.toString(),"main","abc123",time));
  }
  @Test void optInCapturesCurrentGitAndBoundedReferencesWithoutWorkOrCommandText() throws Exception {
    var p=new ActivityProperties();p.setWorkContextEnabled(true);
    var saved=source(p,context(projectId,25)).collect(at).workContext();
    assertEquals(projectId,saved.projectId());assertEquals(at,saved.capturedAt());assertEquals(3,saved.revision());
    assertEquals("main",saved.git().branch());assertEquals(at,saved.git().capturedAt());
    assertEquals(20,saved.items().size());assertTrue(saved.partial());
    assertEquals("src/Main.java",saved.items().getFirst().sources().getFirst().file());
    assertEquals("command-0",saved.items().getFirst().sources().getFirst().toolCallId());
    assertNull(saved.items().get(1).sources().getFirst().file());
    assertFalse(saved.toString().contains("private"));assertFalse(saved.toString().contains(directory.toString()));
  }
  @Test void defaultOffAndOwnerChangeAvoidCrossProjectOrFutureContext() throws Exception {
    var p=new ActivityProperties();
    assertNull(source(p,context(projectId,1)).collect(at).workContext());
    p.setWorkContextEnabled(true);
    assertNull(source(p,context("other",1)).collect(at).workContext());
    var future=new WorkContext(projectId,4,at,at.plusSeconds(1),null,List.of(),Set.of());
    assertNull(source(p,future).collect(at).workContext());
    var calls=new java.util.concurrent.atomic.AtomicInteger();
    var changing=new ActivityObservationContextSource(p,()->calls.getAndIncrement()==0?project():null,
        id->Optional.of(context(id,1)),(root,time)->new WorkContext.GitState("", "main","abc",time));
    assertNull(changing.collect(at).workContext());
  }
  @Test void observationSnapshotSurvivesSQLiteRestartAndMissingWorkContextHistory() throws Exception {
    var p=new ActivityProperties();p.setWorkContextEnabled(true);
    var aggregator=new ActivityEvidenceAggregator(p,List.of(source(p,context(projectId,1))));
    var base=ActivitySemanticTest.dev(0,"Terminal","X");
    var evidence=aggregator.collect(at,new DesktopActivityObserver.Metadata(base.foreground(),List.of()),null);
    var record=new ActivityRecord("observation",at,60,List.of(),base.foreground(),base.inference(),.8,List.of(),0,false,"c",
        new ActivityRecord.Detection(evidence,List.of(),false,"EVIDENCE_ONLY","READY",Map.of(),""));
    var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("activity.db"));
    new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC)).append(record);
    var restarted=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    var service=new ActivityWorkContextService(restarted,id->List.of(),Clock.fixed(at.plusSeconds(60),ZoneOffset.UTC));
    var links=service.links(projectId,"today");assertEquals(1,links.size());
    assertEquals("OBSERVATION_CONTEXT",links.getFirst().basis());assertEquals(3,links.getFirst().revision());
    var text=service.format(links);assertTrue(text.contains("src/Main.java"));assertTrue(text.contains("command-0"));
    assertTrue(text.contains("task-0"));assertFalse(text.contains("private"));
    assertTrue(service.links("other","today").isEmpty());
  }
  @Test void aggregatorDropsSnapshotWhenSelectedProjectIsOverridden() throws Exception {
    var p=new ActivityProperties();p.setWorkContextEnabled(true);
    var aggregator=new ActivityEvidenceAggregator(p,List.of(source(p,context(projectId,1)),
        time->new ActivityEvidenceSource.Contribution("other","other",List.of())));
    var evidence=aggregator.collect(at,new DesktopActivityObserver.Metadata(ActivitySemanticTest.dev(0,"Terminal","X").foreground(),List.of()),null);
    assertNull(evidence.workContext());
  }
  @Test void disabledDoesNoContextOrGitIOAndFailurePreservesProjectButCancellationPropagates() throws Exception {
    var p=new ActivityProperties();var calls=new java.util.concurrent.atomic.AtomicInteger();
    var source=new ActivityObservationContextSource(p,this::project,
        id->{calls.incrementAndGet();throw new IllegalStateException("private failure");},
        (root,time)->{calls.incrementAndGet();return null;});
    assertEquals(projectId,source.collect(at).projectId());assertEquals(0,calls.get());
    p.setWorkContextEnabled(true);
    assertEquals(projectId,source.collect(at).projectId());assertNull(source.collect(at).workContext());
    var cancelled=new ActivityObservationContextSource(p,this::project,
        id->{throw new java.util.concurrent.CancellationException();},(root,time)->null);
    var aggregator=new ActivityEvidenceAggregator(p,List.of(cancelled));
    assertThrows(java.util.concurrent.CancellationException.class,()->aggregator.collect(at,
        new DesktopActivityObserver.Metadata(ActivitySemanticTest.dev(0,"Terminal","X").foreground(),List.of()),null));
  }
  @Test void oldJsonRemainsReadableAndMissingWorkContextStillCapturesGit() throws Exception {
    var json=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    var old=json.readValue("{\"capturedAt\":\"2026-10-05T09:00:00Z\",\"foreground\":null,\"visibleWindows\":[],\"projectName\":\"rei\",\"projectId\":\"p\",\"events\":[],\"history\":null}",ActivityEvidence.class);
    assertNull(old.workContext());
    var p=new ActivityProperties();p.setWorkContextEnabled(true);
    var saved=source(p,null).collect(at).workContext();assertEquals(0,saved.revision());assertTrue(saved.items().isEmpty());
    assertEquals("abc123",saved.git().commit());
  }
  @Test void configurationBindsOptInIndependently() {
    assertFalse(new ActivityProperties().isWorkContextEnabled());
    var binder=new org.springframework.boot.context.properties.bind.Binder(new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
        Map.of("rei.activity.work-context-enabled","true")));
    var configured=binder.bind("rei.activity",ActivityProperties.class).get();
    assertTrue(configured.isWorkContextEnabled());assertFalse(configured.isEnabled());
  }
}
