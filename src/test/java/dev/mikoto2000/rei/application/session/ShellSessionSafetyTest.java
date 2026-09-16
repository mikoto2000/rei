package dev.mikoto2000.rei.application.session;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import static org.assertj.core.api.Assertions.*;

class ShellSessionSafetyTest {
  @TempDir Path temp;
  @Test void oldFilesAreNotReadMigratedOrModifiedByNewShellSession() throws Exception {
    var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    var project = projects.currentContext();
    String oldId = project.conversationId("chat:main");
    var old = temp.resolve("projects").resolve(project.id()).resolve("state/turns")
        .resolve(UUID.nameUUIDFromBytes(oldId.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".json");
    Files.createDirectories(old.getParent());
    // Deliberately unreadable legacy bytes: new history must not inspect or repair them.
    byte[] bytes = "legacy data left exactly as it was".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(old, bytes);
    var repo = new FileSessionRepository(temp.resolve("sessions.json"));
    var query = new SessionQueryService(repo, new ConversationTurnStore(temp));
    assertThat(query.listSessions(null,null,null).items()).isEmpty();
    assertThatThrownBy(()->query.listTurns(oldId,null,null)).isInstanceOf(ResourceNotFoundException.class);
    var shell = new ShellConversationService(projects,new SessionLifecycle(repo,Clock.systemUTC()),(c,p)->{});
    try (var scope = projects.newClient().open()) { shell.submit("new input"); }
    assertThat(query.listSessions(null,null,null).items()).hasSize(1).noneMatch(row->row.sessionId().equals(oldId));
    assertThat(Files.readAllBytes(old)).isEqualTo(bytes);
    assertThat(new SessionQueryService(new FileSessionRepository(temp.resolve("sessions.json")),new ConversationTurnStore(temp))
        .listSessions(null,null,null).items()).hasSize(1);
  }
  @Test void failedCreateOrTouchNeverDispatchesOrChangesCurrentSelection() {
    var projects = new ProjectService(temp,new ProjectRegistry(temp.resolve("projects.json")));
    var disk = new FileSessionRepository(temp.resolve("sessions.json"));
    var fail = new AtomicBoolean(true);
    SessionRepository repo = new SessionRepository() {
      public Optional<SessionMetadata> findById(String id) { return disk.findById(id); }
      public List<SessionMetadata> findPage(String p,CursorKey c,int n) { return disk.findPage(p,c,n); }
      public void accept(SessionMetadata row,Runnable enqueue) {
        if(fail.get()) throw new IllegalStateException("storage unavailable");
        disk.accept(row,enqueue);
      }
    };
    var calls = new AtomicInteger();
    var shell = new ShellConversationService(projects,new SessionLifecycle(repo,Clock.systemUTC()),(c,p)->calls.incrementAndGet());
    try(var scope=projects.newClient().open()) {
      assertThatThrownBy(()->shell.submit("first")).isInstanceOf(IllegalStateException.class);
      assertThat(shell.currentSessionId()).isNull(); assertThat(calls).hasValue(0);
      fail.set(false); var first=shell.submit("first");
      var before=disk.findById(first.conversationId());
      fail.set(true);
      assertThatThrownBy(()->shell.submit("next")).isInstanceOf(IllegalStateException.class);
      assertThat(calls).hasValue(1); assertThat(shell.currentSessionId()).isEqualTo(first.conversationId());
      assertThat(disk.findById(first.conversationId())).isEqualTo(before);
    }
  }
  @Test void rejectedEnqueueRollsBackNewAndContinuedMetadata() {
    var projects=new ProjectService(temp,new ProjectRegistry(temp.resolve("projects.json")));
    var repo=new FileSessionRepository(temp.resolve("sessions.json"));
    var reject=new AtomicBoolean(true);
    var shell=new ShellConversationService(projects,new SessionLifecycle(repo,Clock.systemUTC()),(c,p)->{if(reject.get())throw new RejectedExecutionException();});
    try(var scope=projects.newClient().open()) {
      assertThatThrownBy(()->shell.submit("first")).isInstanceOf(RejectedExecutionException.class);
      assertThat(repo.findPage(null,null,100)).isEmpty(); assertThat(shell.currentSessionId()).isNull();
      reject.set(false);var first=shell.submit("first");var before=repo.findById(first.conversationId());
      reject.set(true);assertThatThrownBy(()->shell.submit("next")).isInstanceOf(RejectedExecutionException.class);
      assertThat(repo.findById(first.conversationId())).isEqualTo(before);
    }
  }
  @Test void concurrentShellAndWebAdmissionsMatchQueueAndPersistentTurnOrder() throws Exception {
    var registry=new ProjectRegistry(temp.resolve("projects.json"));var project=registry.resolve(temp);
    var projects=new ProjectService(temp,registry);var repo=new FileSessionRepository(temp.resolve("sessions.json"));
    var clock=Clock.systemUTC();var lifecycle=new SessionLifecycle(repo,clock);
    var turns=new ConversationTurnStore(temp);var tasks=new ArrayList<Runnable>();
    var order=new ArrayList<String>();
    var router=new ConversationInputRouter(tasks::add,(c,p,q)->turns.startOrdered(c,p,Instant.EPOCH));
    java.util.function.BiConsumer<AgentRunContext,String> enqueue=(c,p)->{order.add(c.runId());router.submit(c,p);};
    var shell=new ShellConversationService(projects,lifecycle,enqueue);
    var client=projects.newClient();String id;
    try(var scope=client.open()){id=shell.submit("first").conversationId();}
    var web=new ChatSubmitService(registry,new SessionRegistry(clock),new RunRegistry(clock),lifecycle,enqueue);
    try(var workers=Executors.newFixedThreadPool(4)) {
      var futures=new ArrayList<Future<?>>();
      for(int i=0;i<20;i++){final int n=i;futures.add(workers.submit(()->{
        if(n%2==0)try(var scope=client.open()){shell.submit("shell "+n);}else web.submit("web "+n,project.id(),id);
      }));}
      for(var future:futures)future.get(5,TimeUnit.SECONDS);
    }
    while(!tasks.isEmpty())tasks.removeFirst().run();
    assertThat(turns.findTurns(id,null,100)).extracting(SessionTurn::runId).containsExactlyElementsOf(order);
    assertThat(repo.findPage(null,null,100)).hasSize(1);
    assertThat(repo.findById(id).orElseThrow().title()).isEqualTo("first");
  }
}
