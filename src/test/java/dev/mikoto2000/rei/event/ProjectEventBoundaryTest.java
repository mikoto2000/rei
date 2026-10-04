package dev.mikoto2000.rei.event;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class ProjectEventBoundaryTest {
  @TempDir Path dir;
  AgentEvent event(String project){return new AgentEvent(UUID.randomUUID().toString(),0,Instant.EPOCH,AgentEventType.AGENT_RUN_COMPLETED,1,"s",null,"r",null,null,new AgentRunCompletedPayload("r",1),project);}
  @Test void boundaryPersistsExactlyOnceAndNestedBoundariesKeepIdentity() {
    var bus=new InMemoryAgentEventBus();var store=new ProjectAgentEventStore(dir);String project=UUID.randomUUID().toString();
    try(var subscriber=new ProjectAgentEventSubscriber(bus,store)) {
      var first=event(project);var second=event(project);bus.subscribe(e->{if(e.id().equals(first.id()))bus.publishBoundary(second);});
      bus.publishBoundary(first);assertEquals(List.of(first.id(),second.id()),store.recent(project,10).stream().map(AgentEvent::id).toList());
    }
  }
  @Test void durabilityFailureReachesBoundaryCallerBeforePublishing() throws Exception {
    Files.writeString(dir.resolve("projects"),"not a directory");var bus=new InMemoryAgentEventBus();var store=new ProjectAgentEventStore(dir);
    var published=new ArrayList<AgentEvent>();bus.subscribe(published::add);
    try(var subscriber=new ProjectAgentEventSubscriber(bus,store)) {
      assertThrows(IllegalStateException.class,()->bus.publishBoundary(event(UUID.randomUUID().toString())));assertTrue(published.isEmpty());
    }
  }
}
