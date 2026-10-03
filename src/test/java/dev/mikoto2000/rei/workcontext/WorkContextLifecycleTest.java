package dev.mikoto2000.rei.workcontext;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.event.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkContextLifecycleTest {
  @Test void autoUpdateDefaultsOffAndEnabledFailureIsIsolatedAndOwnedByOriginalSession() throws Exception {
    var service=mock(WorkContextService.class);var bus=new InMemoryAgentEventBus();var context=new AgentRunContext("run","session",Path.of("."),"project");
    try(var disabled=new WorkContextAutomation(service,new WorkContextProperties(false,true,1200,12000,2,20),bus)) {disabled.afterTerminal(context);}
    verifyNoInteractions(service);
    var ended=new CountDownLatch(1);var events=new CopyOnWriteArrayList<AgentEvent>();
    bus.subscribe(e->{events.add(e);if(e.type()==AgentEventType.WORK_CONTEXT_UPDATE_FAILED)ended.countDown();});
    when(service.update("session","run")).thenThrow(new IllegalStateException("LLM unavailable"));
    try(var enabled=new WorkContextAutomation(service,new WorkContextProperties(true,true,1200,12000,2,20),bus)) {
      enabled.afterTerminal(context);assertTrue(ended.await(5,TimeUnit.SECONDS));
    }
    assertEquals("project",events.getLast().projectId());assertEquals("session",events.getLast().sessionId());
    assertNull(events.getLast().runId());assertEquals("run",((WorkContextPayload)events.getLast().payload()).sourceRunId());
  }
  @Test void resumeIsShownOncePerSessionAndCanBeDisabled() {
    var service=mock(WorkContextService.class);when(service.current(anyString())).thenReturn(Optional.empty());
    var p=new ProjectContext(UUID.randomUUID().toString(),"project",Path.of("."));var seen=new HashSet<String>();
    var presenter=new WorkContextPresenter(service,new WorkContextProperties(false,true,1200,12000,2,20),mock(WorkContextGit.class));
    assertTrue(presenter.present(p,"s1",seen).orElseThrow().contains("まだありません"));
    assertTrue(presenter.present(p,"s1",seen).isEmpty());assertTrue(presenter.present(p,"s2",seen).isPresent());
    assertTrue(new WorkContextPresenter(service,new WorkContextProperties(false,false,1200,12000,2,20),mock(WorkContextGit.class)).present(p,"s3",seen).isEmpty());
  }
}
