package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class EventPageTest {
  @TempDir Path dir;
  final String project=UUID.randomUUID().toString();
  AgentEvent event(int n){return new AgentEvent("event-"+n,0,Instant.EPOCH,AgentEventType.AGENT_RUN_COMPLETED,1,
      "session",null,"source",null,null,new AgentRunCompletedPayload("source",1),project);}
  Path file(){return dir.resolve("projects").resolve(project).resolve("events/events.jsonl");}
  @Test void pagesBoundRecordsAndPreserveEveryEventInOrder() {
    var store=new ProjectAgentEventStore(dir);for(int i=0;i<300;i++)store.append(event(i));
    var one=store.readPage(project,0,false);assertEquals(128,one.events().size());
    var two=store.readPage(project,one.nextOffset(),one.discardingLine());assertEquals("event-128",two.events().getFirst().id());
    var three=store.readPage(project,two.nextOffset(),two.discardingLine());assertEquals(44,three.events().size());
    assertEquals("event-299",three.events().getLast().id());
    assertTrue(store.readPage(project,three.nextOffset(),false).events().isEmpty());
  }
  @Test void oversizedAndInterruptedLinesCannotProduceFragmentEvents() throws Exception {
    Files.createDirectories(file().getParent());Files.writeString(file(),"x".repeat(300*1024));
    var store=new ProjectAgentEventStore(dir);var first=store.readPage(project,0,false);
    assertEquals(256*1024,first.nextOffset());assertTrue(first.discardingLine());
    // Append a valid-looking fragment without a separator; it must remain part of the oversized line.
    var separate=new ProjectAgentEventStore(dir.resolve("separate"));separate.append(event(9));
    String encoded=Files.readString(dir.resolve("separate/projects").resolve(project).resolve("events/events.jsonl"));
    Files.writeString(file(),encoded,StandardOpenOption.APPEND);store.append(event(10));
    var second=store.readPage(project,first.nextOffset(),true);
    assertEquals(List.of("event-10"),second.events().stream().map(AgentEvent::id).toList());
    Files.writeString(file(),"{partial",StandardOpenOption.APPEND);
    var tail=store.readPage(project,second.nextOffset(),false);assertEquals(second.nextOffset(),tail.nextOffset());
    store.append(event(11));var recovered=store.readPage(project,tail.nextOffset(),false);
    assertEquals(List.of("event-11"),recovered.events().stream().map(AgentEvent::id).toList());
  }
  @Test void truncationAndMissingFilesResetCursor() throws Exception {
    var store=new ProjectAgentEventStore(dir);assertEquals(0,store.readPage(project,123,true).nextOffset());
    store.append(event(1));var page=store.readPage(project,0,false);
    Files.writeString(file(),"",StandardOpenOption.TRUNCATE_EXISTING);
    assertEquals(0,store.readPage(project,page.nextOffset(),false).nextOffset());
    assertThrows(IllegalArgumentException.class,()->store.readPage(project,-1,false));
  }
}
