package dev.mikoto2000.rei.llm.capture;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.application.session.*;
@Tag("integration")
class CaptureAdmissionTest {
  @TempDir Path dir;
  @Test void queuedCancellationEndsCaptureAndAnewReservationCanBeCreated(){
    var tasks=new ArrayList<Runnable>();var router=new ConversationInputRouter(tasks::add,(c,p,q)->{});var store=new CaptureStore();router.setRequestCaptures(store);
    var projects=new ProjectService(dir,new ProjectRegistry(dir.resolve("projects.json")));
    var shell=new ShellConversationService(projects,new SessionLifecycle(new dev.mikoto2000.rei.conversation.FileSessionRepository(dir.resolve("sessions.json")),java.time.Clock.systemUTC()),router::submit);
    shell.setCaptures(store);
    try(var scope=projects.newClient().open()){
      shell.submit("first");store.reserve(projects.currentClient(),shell.currentSessionId());var captured=shell.submit("second");
      assertThat(store.sessions().getFirst().runId()).isEqualTo(captured.runId());assertThat(router.cancelQueued(captured.runId())).isTrue();
      assertThat(store.sessions().getFirst().outcome()).isEqualTo("CANCELLED");assertThat(store.sessions().getFirst().ended()).isNotNull();
      store.reserve(projects.currentClient(),shell.currentSessionId());
    }
  }
  @Test void deferredExecutorRejectionEndsTheAcceptedCapture(){
    var tasks=new ArrayList<Runnable>();var dispatches=new java.util.concurrent.atomic.AtomicInteger();
    var router=new ConversationInputRouter(task->{if(dispatches.incrementAndGet()==1)tasks.add(task);else throw new RejectedExecutionException("test");},(c,p,q)->{});
    var store=new CaptureStore();router.setRequestCaptures(store);
    var projects=new ProjectService(dir,new ProjectRegistry(dir.resolve("projects.json")));
    var shell=new ShellConversationService(projects,new SessionLifecycle(new dev.mikoto2000.rei.conversation.FileSessionRepository(dir.resolve("sessions.json")),java.time.Clock.systemUTC()),router::submit);shell.setCaptures(store);
    try(var scope=projects.newClient().open()){
      shell.submit("first");store.reserve(projects.currentClient(),shell.currentSessionId());shell.submit("second");
      assertThatThrownBy(()->tasks.removeFirst().run()).isInstanceOf(RejectedExecutionException.class);
      assertThat(store.sessions().getFirst().outcome()).isEqualTo("START_FAILED");assertThat(store.sessions().getFirst().ended()).isNotNull();
    }
  }
}
