package dev.mikoto2000.rei.slack;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.event.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class SlackRuntimeTest {
  @TempDir Path root;
  private SlackStore store;private SlackProperties p;private ProjectRegistry projects;
  private SessionLifecycle sessions;private RunRegistry registry;private RunService runs;
  private ConversationInputRouter router;
  private final Map<String,SessionMetadata> saved=new HashMap<>();
  private SlackRuntime runtime() {return new SlackRuntime(p,store,"B",projects,sessions,registry,runs,router,Clock.systemUTC());}
  @BeforeEach void setup() {
    store=new SlackStore(root);p=new SlackProperties();p.setAllowedWorkspaceIds(Set.of("T"));p.setAllowedUserIds(Set.of("U"));p.setAllowedChannelIds(Set.of("D","C"));
    projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);p.setDmProjectId(project.id());p.setChannelProjects(Map.of("C",project.id()));
    var repo=mock(SessionRepository.class);when(repo.findById(any())).thenAnswer(i->Optional.ofNullable(saved.get(i.getArgument(0))));
    doAnswer(i->{var m=(SessionMetadata)i.getArgument(0);saved.put(m.sessionId(),m);((Runnable)i.getArgument(1)).run();return null;}).when(repo).accept(any(),any());
    sessions=new SessionLifecycle(repo,Clock.systemUTC());registry=new RunRegistry(Clock.systemUTC());router=mock(ConversationInputRouter.class);
    runs=new RunService(registry,mock(AgentEventBus.class),new AgentEventFactory(Clock.systemUTC()),new dev.mikoto2000.rei.core.service.CommandCancellationService(),router::cancelQueued);
  }
  private SlackMessage m(String ts,String thread,String text) {return new SlackMessage("E"+ts,"T","D",ts,thread,"U",null,null,"message","im",text);}
  @Test void submitsThroughExistingRunQueueAndKeepsThreadSession() {
    var runtime=runtime();assertTrue(runtime.accept(m("1",null,"hello")));runtime.tick();
    var first=store.latest(m("1",null,"hello")).orElseThrow();
    verify(router).submit(any(AgentRunContext.class),eq("hello"),any());assertNotNull(first.run());
    assertTrue(runtime.accept(m("2","1","next")));runtime.tick();var second=store.latest(m("2","1","next")).orElseThrow();assertEquals(first.session(),second.session());
    assertTrue(runtime.accept(m("3",null,"separate")));runtime.tick();assertNotEquals(first.session(),store.latest(m("3",null,"separate")).orElseThrow().session());
  }
  @Test void restartRecoversAcceptedButNeverReexecutesAnAlreadyDispatchedEvent() {
    assertTrue(runtime().accept(m("1",null,"hello")));runtime().tick();runtime().tick();
    assertFalse(runtime().accept(new SlackMessage("another","T","D","1",null,"U",null,null,"app_mention","im","hello")));
    verify(router,times(1)).submit(any(AgentRunContext.class),anyString(),any());
  }
  @Test void failedSendRetriesOutboxWithoutExecutingRunAgain() {
    var runtime=runtime();runtime.accept(m("1",null,"hello"));runtime.tick();
    runtime.send(row->SlackTransport.Delivery.retry(2));runtime.send(row->SlackTransport.Delivery.sent("10"));
    verify(router,times(1)).submit(any(AgentRunContext.class),anyString(),any());
  }
  @Test void statusAndCancelDoNotStartAnAgentRun() {
    var runtime=runtime();runtime.accept(m("1",null,"hello"));runtime.tick();var receipt=store.latest(m("1",null,"hello")).orElseThrow();
    runtime.accept(m("2","1","/rei-status"));runtime.tick();verify(router,times(1)).submit(any(AgentRunContext.class),anyString(),any());
    when(router.containsRun(anyString(),anyString())).thenReturn(true);
    runtime.accept(m("3","1","/rei-cancel"));runtime.tick();
    assertEquals(1,registry.runIds().size());
    assertEquals("CANCEL_REQUESTED",store.byRun(receipt.run()).orElseThrow().state());
    assertTrue(store.outbox(Long.MAX_VALUE).stream().anyMatch(r->r.text().contains("キャンセルを受け付け")));
    assertTrue(store.outbox(Long.MAX_VALUE).stream().noneMatch(r->r.text().contains("キャンセル完了")));
    when(router.containsRun(anyString(),anyString())).thenReturn(false);runtime.tick();
    assertTrue(store.outbox(Long.MAX_VALUE).stream().anyMatch(r->r.text().contains("キャンセル完了")));
  }
  @Test void permissionRequiredUsesExistingApprovalAndRepliesToOwningThread() {
    var runtime=runtime();runtime.accept(m("1",null,"hello"));runtime.tick();var r=store.latest(m("1",null,"hello")).orElseThrow();
    runtime.onEvent(new AgentEvent("event",1,Clock.systemUTC().instant(),AgentEventType.TOOL_FAILED,1,r.session(),null,r.run(),null,null,new ToolFailedPayload("M","write",new ErrorInformation("PermissionRequired","unsafe raw data",null)),r.project()));
    assertEquals("WAITING_APPROVAL",store.byRun(r.run()).orElseThrow().state());
    assertTrue(store.outbox(Long.MAX_VALUE).stream().anyMatch(row->row.thread().equals("1")&&row.text().contains("承認待ち")));
    assertTrue(store.outbox(Long.MAX_VALUE).stream().noneMatch(row->row.text().contains("unsafe raw data")));
  }
  @Test void unrelatedEventNeverLeaksToSlack() {
    var runtime=runtime();runtime.accept(m("1",null,"hello"));runtime.tick();
    runtime.onEvent(new AgentEvent("event",1,Clock.systemUTC().instant(),AgentEventType.MESSAGE_COMPLETED,1,"other",null,"other",null,null,new MessageCompletedPayload("M","assistant","private"),"other"));
    assertTrue(store.outbox(Long.MAX_VALUE).stream().noneMatch(r->r.text().contains("private")));
  }
}
