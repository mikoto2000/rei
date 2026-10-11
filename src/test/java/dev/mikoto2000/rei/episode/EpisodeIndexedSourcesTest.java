package dev.mikoto2000.rei.episode;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
@Tag("integration")
class EpisodeIndexedSourcesTest {
  @TempDir Path root;
  @BeforeEach void prepare() throws Exception {try(var migration=new StorageMigrationCoordinator(root)){migration.prepare();}}
  @Test void rangesAndRunLookupSurviveRestartWithoutReadingEarlierRecords() {
    var store=new SqliteConversationTurnStore(root);
    for(int i=0;i<100;i++) {
      var owner=new AgentRunContext("r"+i,"chat:fixture",root);store.start(owner,"request "+i,Instant.EPOCH.plusSeconds(i));
      store.finish(owner,ConversationTurnStore.Status.COMPLETED,"result "+i);
    }
    var restarted=new SqliteConversationTurnStore(root);
    assertEquals(100,restarted.turnCount("chat:fixture"));
    assertEquals(java.util.List.of("r98","r99"),restarted.readRange("chat:fixture",98,50).stream().map(ConversationTurnStore.Turn::runId).toList());
    assertEquals("result 50",restarted.findRun("chat:fixture","r50").orElseThrow().assistantMessage());
    assertTrue(restarted.findRun("chat:other","r50").isEmpty());
  }
  @Test void exactEventLookupDoesNotDependOnRecentWindowAndRespectsProject() {
    String project=java.util.UUID.randomUUID().toString();var store=new SqliteProjectAgentEventStore(root);
    var first=store.append(new AgentEvent("event1",0,Instant.EPOCH,AgentEventType.TOOL_COMPLETED,1,"s","r","r",null,null,new ToolCompletedPayload("call","test",1,"passed",null,null,null,null),project));
    store.append(new AgentEvent("event2",0,Instant.EPOCH.plusSeconds(1),AgentEventType.TOOL_COMPLETED,1,"s","r2","r2",null,null,new ToolCompletedPayload("call2","test",1,"passed",null,null,null,null),project));
    assertEquals("event2",store.recent(project,1).getFirst().id());
    assertEquals(first,store.findEvent(project,"event1").orElseThrow());
    assertTrue(store.findEvent(java.util.UUID.randomUUID().toString(),"event1").isEmpty());
  }
}
