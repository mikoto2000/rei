package dev.mikoto2000.rei.workcontext;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mikoto2000.rei.workcontext.WorkContext.*;
class WorkContextServiceTest {
  @TempDir Path temp;
  WorkContextRepository repo; SessionRepository sessions; ProjectService projects;
  ConversationTurnStore turns; WorkContextExtractor extractor; WorkContextService service; ProjectContext a,b;
  WorkContextProperties properties=new WorkContextProperties(false,true,1200,12000,2,20);
  @BeforeEach void setup() throws Exception {
    a=new ProjectContext(UUID.randomUUID().toString(),"same",Files.createDirectories(temp.resolve("a")));
    b=new ProjectContext(UUID.randomUUID().toString(),"same",Files.createDirectories(temp.resolve("b")));
    var ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+temp.resolve("work.db"));repo=new WorkContextRepository(ds);
    sessions=mock(SessionRepository.class);projects=mock(ProjectService.class);extractor=mock(WorkContextExtractor.class);
    when(projects.registeredProjects()).thenReturn(List.of(a,b));when(projects.currentContext()).thenReturn(b);
    when(sessions.findById("session-a")).thenReturn(Optional.of(new SessionMetadata("session-a",a.id(),"title",Instant.now(),Instant.now())));
    turns=ConversationTurnStore.inMemory();
    var context=new AgentRunContext("run-a","session-a",a.root(),a.id());turns.start(context,"implement");turns.finish(context,ConversationTurnStore.Status.COMPLETED,"implemented; tests not run");
    when(extractor.extract(anyList(),anyList(),anyString())).thenAnswer(call->{
      List<Evidence> e=call.getArgument(0);return List.of(new WorkContextCandidate("ADD",null,Kind.PENDING,"test Native","not run",Status.OPEN,List.of(e.getFirst().id())));
    });
    service=new WorkContextService(repo,sessions,projects,turns,new ProjectAgentEventStore(temp),extractor,new WorkContextGit(),properties,Clock.systemUTC());
  }
  @Test void sessionOwnershipWinsOverChangedUiProjectAndRepeatedRunsSkipLlm() {
    service.update("session-a",null);
    assertTrue(repo.current(b.id()).isEmpty());assertEquals(1,repo.current(a.id()).orElseThrow().items().size());
    service.update("session-a",null);verify(extractor,times(1)).extract(anyList(),anyList(),anyString());
  }
  @Test void extractionFailureAndUnknownSourcesKeepLastSnapshot() {
    service.update("session-a",null); var old=repo.current(a.id()).orElseThrow();
    var context=new AgentRunContext("run-2","session-a",a.root(),a.id());turns.start(context,"more");turns.finish(context,ConversationTurnStore.Status.FAILED);
    doThrow(new IllegalArgumentException("invalid JSON")).when(extractor).extract(anyList(),anyList(),anyString());
    assertThrows(IllegalArgumentException.class,()->service.update("session-a",null));assertEquals(old,repo.current(a.id()).orElseThrow());
    doReturn(List.of(new WorkContextCandidate("ADD",null,Kind.PENDING,"invented","",Status.OPEN,List.of("fake")))).when(extractor).extract(anyList(),anyList(),anyString());
    assertThrows(IllegalArgumentException.class,()->service.update("session-a",null));assertEquals(old,repo.current(a.id()).orElseThrow());
  }
  @Test void missingSessionAndMissingProjectDoNotCreateDirectories() {
    when(sessions.findById("missing")).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class,()->service.update("missing",null));
    assertThrows(IllegalArgumentException.class,()->service.update(null,null));
    when(projects.registeredProjects()).thenReturn(List.of(b));
    assertThrows(IllegalArgumentException.class,()->service.update("session-a",null));
  }
  @Test void concurrentSessionsMergeWithoutLostItemsAndExplicitEditsRetainHistory() throws Exception {
    when(sessions.findById("session-2")).thenReturn(Optional.of(new SessionMetadata("session-2",a.id(),"second",Instant.now(),Instant.now())));
    var run=new AgentRunContext("run-2","session-2",a.root(),a.id());turns.start(run,"second task");turns.finish(run,ConversationTurnStore.Status.COMPLETED);
    doAnswer(call->{List<Evidence> e=call.getArgument(0);return List.of(new WorkContextCandidate("ADD",null,Kind.PENDING,e.getFirst().text(),"",Status.OPEN,List.of(e.getFirst().id())));})
        .when(extractor).extract(anyList(),anyList(),anyString());
    try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var one=workers.submit(()->service.update("session-a",null));var two=workers.submit(()->service.update("session-2",null));
      one.get(5,java.util.concurrent.TimeUnit.SECONDS);two.get(5,java.util.concurrent.TimeUnit.SECONDS);
    }
    var old=repo.current(a.id()).orElseThrow();assertEquals(2,old.items().size());assertEquals(2,old.processedRuns().size());
    var id=old.items().getFirst().id();var now=Instant.now();var evidence=new Evidence("explicit",Origin.USER,"session-a","run-a","run-a",null,null,null,now,now,"complete it");
    var complete=service.edit(a.id(),old.revision(),id,"STATUS",null,"verified by user",Status.COMPLETED,evidence);
    var reopen=service.edit(a.id(),complete.revision(),id,"STATUS",null,"more work",Status.OPEN,evidence);
    var withdrawn=service.edit(a.id(),reopen.revision(),id,"STATUS",null,"cancel task",Status.WITHDRAWN,evidence);
    assertEquals(Status.WITHDRAWN,withdrawn.items().getFirst().status());
    assertEquals(Status.OPEN,service.revision(a.id(),old.revision()).items().getFirst().status());
    assertThrows(ConcurrentModificationException.class,()->service.edit(a.id(),old.revision(),id,"CORRECT","stale","",Status.OPEN,evidence));
  }
  @Test void cancellationAndWorkTimeMetadataAreRetainedWithoutProjectDirectoryCreation() {
    var run=new AgentRunContext("run-meta","session-a",a.root(),a.id());turns.start(run,"metadata");
    service.captureRunStart(run);var captured=turns.read("session-a").getLast().metadata().get("workContext.gitCapturedAt");
    turns.finish(run,ConversationTurnStore.Status.CANCELLED);assertEquals(captured,turns.read("session-a").getLast().metadata().get("workContext.gitCapturedAt"));
    Thread.currentThread().interrupt();
    try {assertThrows(java.util.concurrent.CancellationException.class,()->service.update("session-a",null));}
    finally {Thread.interrupted();}
    assertTrue(repo.current(a.id()).isEmpty());
    service.update("session-a",null);assertEquals(Instant.parse(captured),repo.current(a.id()).orElseThrow().git().capturedAt());
  }
  @Test void explicitSaveCanIncludeActiveSessionButFinalUpdateStillProcessesItsOutcome() {
    var run=new AgentRunContext("active","session-a",a.root(),a.id());turns.start(run,"verify Native");
    service.update("session-a",null,true);
    assertFalse(repo.current(a.id()).orElseThrow().processedRuns().contains("active"));
    turns.finish(run,ConversationTurnStore.Status.CANCELLED);
    service.update("session-a","active");
    assertTrue(repo.current(a.id()).orElseThrow().processedRuns().contains("active"));
    assertEquals(1,repo.current(a.id()).orElseThrow().items().size());
  }
  @Test void cancelledAssistantCompletionCannotCompleteWholeCurrentWork() {
    var run=new AgentRunContext("cancelled","session-a",a.root(),a.id());turns.start(run,"implement everything");turns.finish(run,ConversationTurnStore.Status.CANCELLED,"everything done");
    doReturn(List.of(new WorkContextCandidate("ADD",null,Kind.CURRENT_WORK,"implement everything","reported completion",Status.COMPLETED,List.of("cancelled:assistant"),Origin.ASSISTANT)))
        .when(extractor).extract(anyList(),anyList(),anyString());
    service.update("session-a",null);
    assertEquals(Status.UNCONFIRMED,repo.current(a.id()).orElseThrow().items().getFirst().status());
  }
  @Test void aRunningRecordLeftByProcessExitDoesNotBlockANewerFinalizedRun() {
    var stranded=new AgentRunContext("stranded","session-a",a.root(),a.id());turns.start(stranded,"old interrupted process");
    var newer=new AgentRunContext("newer","session-a",a.root(),a.id());turns.start(newer,"new task");turns.finish(newer,ConversationTurnStore.Status.COMPLETED,"new task report");
    assertTrue(service.update("session-a","newer").isPresent());
    assertTrue(repo.current(a.id()).orElseThrow().processedRuns().contains("newer"));
    assertFalse(repo.current(a.id()).orElseThrow().processedRuns().contains("stranded"));
  }
}
