package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.memory.configuration.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.topic.DefaultAgentActivityTracker;

@Tag("integration")
class AutoSleepMetadataRefreshTest {
  @TempDir Path dir;
  final String project="00000000-0000-0000-0000-000000000001";
  final Instant initial=Instant.parse("2026-10-06T00:00:00Z");
  final java.util.concurrent.atomic.AtomicReference<Instant> time=new java.util.concurrent.atomic.AtomicReference<>(initial);
  final Clock clock=new Clock() {
    public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){return this;}
    public Instant instant(){return time.get();}
  };
  final SleepService sleep=mock(SleepService.class);
  final DefaultAgentActivityTracker activity=new DefaultAgentActivityTracker(Clock.fixed(initial.minusSeconds(600),ZoneOffset.UTC));
  AutoSleepService service;
  @AfterEach void close(){if(service!=null)service.close();}
  void setup(SessionRepository saved,boolean enabled) {
    service=new AutoSleepService(sleep,new MemoryProperties(true,20,80,10,3,2000,60,null),
        new AutoSleepProperties(enabled,Duration.ofSeconds(5),2,Duration.ofMinutes(1)),activity,clock);
    var projects=mock(ProjectService.class);
    when(projects.completionProjects()).thenReturn(List.of(new ProjectContext(project,"p",dir)));
    service.setStartupSources(saved,projects);
  }
  SessionMetadata metadata(String id){return new SessionMetadata(id,project,"old",initial,initial);}
  @Test void externalMetadataCreatedAfterStartupIsDiscoveredWithoutNewChat() throws Exception {
    var file=dir.resolve("sessions.json");
    var saved=new FileSessionRepository(file);setup(saved,true);service.tick();
    var external=new FileSessionRepository(file);external.accept(metadata("new"),()->{});
    assertTrue(saved.completionSnapshot().isEmpty());
    when(sleep.unsleptTurns("new")).thenReturn(2L);
    var done=new CountDownLatch(1);
    doAnswer(a->{done.countDown();return null;}).when(sleep).sleep(eq("new"),eq(project),eq(false),any());
    time.set(initial.plusSeconds(61));service.tick();assertTrue(done.await(2,TimeUnit.SECONDS));
  }
  @Test void pagesContinueAcrossTicksWithoutRecapturingStartupSnapshot() throws Exception {
    var saved=mock(SessionRepository.class);setup(saved,true);service.tick();
    var first=new ArrayList<SessionMetadata>();for(int i=0;i<100;i++)first.add(metadata(String.format("old%03d",i)));
    var cursor=new CursorKey(initial,"old099");
    when(saved.findPage(null,null,100)).thenReturn(first);
    when(saved.findPage(null,cursor,100)).thenReturn(List.of(metadata("old100")));
    when(sleep.unsleptTurns("old100")).thenReturn(2L);
    var done=new CountDownLatch(1);doAnswer(a->{done.countDown();return null;})
        .when(sleep).sleep(eq("old100"),eq(project),eq(false),any());
    time.set(initial.plusSeconds(61));service.tick();
    verify(sleep,never()).unsleptTurns("old100");
    service.tick();assertTrue(done.await(2,TimeUnit.SECONDS));
    verify(saved,times(1)).completionSnapshot();verify(saved,times(1)).findPage(null,null,100);
    verify(saved,times(1)).findPage(null,cursor,100);
  }
  @Test void failedRefreshIsContainedRateLimitedAndCanRecover() throws Exception {
    var saved=mock(SessionRepository.class);setup(saved,true);service.tick();
    when(saved.findPage(null,null,100)).thenThrow(new IllegalStateException("corrupt metadata"))
        .thenReturn(List.of(metadata("recovered")));
    time.set(initial.plusSeconds(61));assertDoesNotThrow(service::tick);service.tick();
    verify(saved,times(1)).findPage(null,null,100);verifyNoInteractions(sleep);
    when(sleep.unsleptTurns("recovered")).thenReturn(2L);
    var done=new CountDownLatch(1);doAnswer(a->{done.countDown();return null;})
        .when(sleep).sleep(eq("recovered"),eq(project),eq(false),any());
    time.set(initial.plusSeconds(122));service.tick();assertTrue(done.await(2,TimeUnit.SECONDS));
    verify(saved,times(2)).findPage(null,null,100);
  }
  @Test void disabledBusyAndRecentActivityPreventPersistentRefresh() {
    var saved=mock(SessionRepository.class);setup(saved,false);service.tick();verifyNoInteractions(saved,sleep);
    service.close();setup(saved,true);service.tick();time.set(initial.plusSeconds(61));
    activity.recordAgentStarted(time.get());service.tick();activity.recordAgentCompleted(time.get());service.tick();
    verify(saved,never()).findPage(any(),any(),anyInt());verifyNoInteractions(sleep);
    time.set(initial.plusSeconds(67));service.tick();verify(saved,times(1)).findPage(null,null,100);
  }
  @Test void refreshedRowsKeepRegisteredProjectAndEncodedSessionBoundaries() {
    var saved=mock(SessionRepository.class);setup(saved,true);service.tick();
    var invalid=new SessionMetadata("foreign","00000000-0000-0000-0000-000000000002","foreign",initial,initial);
    var mismatch=new SessionMetadata("project:00000000-0000-0000-0000-000000000002:chat:00000000-0000-0000-0000-000000000003",
        project,"mismatch",initial,initial);
    when(saved.findPage(null,null,100)).thenReturn(List.of(invalid,mismatch,metadata("valid")));
    time.set(initial.plusSeconds(61));service.tick();
    verify(sleep,times(1)).unsleptTurns("valid");verify(sleep,never()).unsleptTurns("foreign");
    verify(sleep,never()).unsleptTurns(mismatch.sessionId());verify(sleep,never()).sleep(anyString(),anyString(),anyBoolean(),any());
  }
  @Test void oversizedProviderPageIsRejectedBeforeHistoryReads() {
    var saved=mock(SessionRepository.class);setup(saved,true);service.tick();
    var rows=new ArrayList<SessionMetadata>();for(int i=0;i<101;i++)rows.add(metadata("old"+i));
    when(saved.findPage(null,null,100)).thenReturn(rows);
    time.set(initial.plusSeconds(61));assertDoesNotThrow(service::tick);service.tick();
    verify(saved,times(1)).findPage(null,null,100);verifyNoInteractions(sleep);
  }
}
