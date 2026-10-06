package dev.mikoto2000.rei.core.dependency;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.event.*;

@Tag("integration")
class DependencyObservationServiceTest {
  @TempDir Path dir;final Instant now=Instant.parse("2026-10-04T00:00:00Z");
  final String project=UUID.randomUUID().toString();
  PersistentDependencyRepository repo(Instant time){return new PersistentDependencyRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("deps.db")),Clock.fixed(time,ZoneOffset.UTC));}
  PersistentDependencyRepository.Entry create(PersistentDependencyRepository repo){return repo.create(new AgentRunContext("r","s",dir,project),new DependencySpec(DependencySpec.Kind.FILE_EXISTS,"result",null),Duration.ofHours(1),List.of());}
  ToolPermissionPolicy policy(boolean enabled){return new ToolPermissionPolicy(new ToolPermissionProperties(enabled,null,null,null));}
  @Test void automaticObservationsRequireEnabledWatcherAndEnforcedAutoPermission() {
    var repo=repo(now);var entry=create(repo);var probe=mock(DependencyProbe.class);var events=new ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    var disabled=new DependencyObservationService(repo,probe,new DependencyWatcherProperties(false),policy(true),events::add);
    disabled.tick();verifyNoInteractions(probe);
    var legacy=new DependencyObservationService(repo,probe,new DependencyWatcherProperties(true),policy(false),events::add);
    legacy.tick();verifyNoInteractions(probe);
    when(probe.probe(any())).thenReturn(new DependencyObservation(entry.id(),DependencyState.COMPLETED,"file_exists"));
    var active=new DependencyObservationService(repo,probe,new DependencyWatcherProperties(true),policy(true),events::add);active.tick();
    assertEquals(DependencyState.COMPLETED,repo.get(project,entry.id()).state());verify(probe,times(1)).probe(any());
  }
  @Test void deadlineAndPrerequisitesDoNotProbeAndStaleCompletionCannotUndoCancel() {
    var repo=repo(now);var parent=create(repo);var child=repo.create(new AgentRunContext("r","s",dir,project),new DependencySpec(DependencySpec.Kind.FILE_EXISTS,"child",null),Duration.ofHours(1),List.of(parent.id()));
    var probe=mock(DependencyProbe.class);var service=new DependencyObservationService(repo,probe,new DependencyWatcherProperties(false),policy(true),e->{});
    assertEquals(DependencyState.BLOCKED,service.inspect(project,child.id(),false).state());verifyNoInteractions(probe);
    when(probe.probe(any())).thenAnswer(invocation->{repo.cancel(project,parent.id());return new DependencyObservation(parent.id(),DependencyState.COMPLETED,"file_exists");});
    assertEquals(DependencyState.CANCELLED,service.inspect(project,parent.id(),false).state());
    var late=new DependencyObservationService(repo(now.plusSeconds(3600)),probe,new DependencyWatcherProperties(false),policy(true),e->{});
    clearInvocations(probe);assertEquals(DependencyState.FAILED,late.inspect(project,child.id(),false).state());verifyNoInteractions(probe);
  }
  @Test void durableFactRetriesKeepIdentityAndDriveExistingEventScheduler() {
    var repo=repo(now);var entry=create(repo);var probe=mock(DependencyProbe.class);
    when(probe.probe(any())).thenReturn(new DependencyObservation(entry.id(),DependencyState.COMPLETED,"file_exists"));
    var bus=new InMemoryAgentEventBus();var store=new ProjectAgentEventStore(dir.resolve("history"));
    try(var subscriber=new ProjectAgentEventSubscriber(bus,store)) {
      var scheduler=new dev.mikoto2000.rei.temporal.PersistentAgentScheduler(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("timer.db")),Clock.fixed(now,ZoneOffset.UTC));
      String timer;try(var scope=AgentRunScope.open(new AgentRunContext("r","s",dir,project))) {
        timer=scheduler.scheduleOnEvent(entry.id(),AgentEventType.DEPENDENCY_COMPLETED,Duration.ofHours(1),"Continue","s").id();
      }scheduler.activate(project,timer);
      try(var triggers=new dev.mikoto2000.rei.temporal.AgentEventTriggerService(scheduler,bus,store)) {
        var failed=new DependencyObservationService(repo,probe,new DependencyWatcherProperties(false),policy(true),e->{throw new IllegalStateException("delivery unavailable");});
        failed.inspect(project,entry.id(),false);assertFalse(repo.pendingFacts().isEmpty());String eventId=repo.pendingFacts().getLast().eventId();
        var restored=new DependencyObservationService(repo(now),probe,new DependencyWatcherProperties(false),policy(true),bus);
        restored.tick();assertTrue(repo.pendingFacts().isEmpty());assertEquals(timer,scheduler.claimDue().orElseThrow().task().id());
        var matching=store.recent(project,10).stream().filter(e->e.id().equals(eventId)).toList();assertEquals(1,matching.size());
        assertEquals(AgentEventType.DEPENDENCY_COMPLETED,matching.getFirst().type());
        assertNull(matching.getFirst().runId());assertEquals(entry.id(),matching.getFirst().correlationId());
      }
    }
  }
  @Test void readOnlyInspectionCannotPerformHttpAndAutomaticNetworkWaitRequiresGrant() {
    var repo=repo(now);var entry=repo.create(new AgentRunContext("r","s",dir,project),new DependencySpec(DependencySpec.Kind.HTTP_STATUS,"https://example.com","200"),Duration.ofHours(1),List.of());
    var probe=mock(DependencyProbe.class);var service=new DependencyObservationService(repo,probe,new DependencyWatcherProperties(true),policy(true),e->{});
    assertThrows(IllegalArgumentException.class,()->service.inspect(project,entry.id(),false));service.tick();verifyNoInteractions(probe);
    assertEquals("permission_required",repo.get(project,entry.id()).reason());
  }
  @Test void missingOrMovedOwnerSessionBlocksObservationUntilOwnerIsValid() {
    var repo=repo(now);var entry=create(repo);var probe=mock(DependencyProbe.class);
    var projects=mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var sessions=mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    when(projects.registeredProjects()).thenReturn(List.of(new dev.mikoto2000.rei.core.project.ProjectContext(project,"test",dir)));
    var service=new DependencyObservationService(repo,probe,new DependencyWatcherProperties(false),policy(true),e->{},projects,sessions);
    when(sessions.findById("s")).thenReturn(Optional.empty());
    assertEquals(DependencyState.BLOCKED,service.inspect(project,entry.id(),false).state());
    when(sessions.findById("s")).thenReturn(Optional.of(new dev.mikoto2000.rei.application.session.SessionMetadata("s","other","test",now,now)));
    assertEquals(DependencyState.BLOCKED,service.inspect(project,entry.id(),false).state());verifyNoInteractions(probe);
    when(sessions.findById("s")).thenReturn(Optional.of(new dev.mikoto2000.rei.application.session.SessionMetadata("s",project,"test",now,now)));
    when(probe.probe(any())).thenReturn(new DependencyObservation(entry.id(),DependencyState.COMPLETED,"file_exists"));
    assertEquals(DependencyState.COMPLETED,service.inspect(project,entry.id(),false).state());verify(probe).probe(any());
  }

}
