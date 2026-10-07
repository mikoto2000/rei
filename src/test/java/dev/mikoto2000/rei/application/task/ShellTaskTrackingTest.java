package dev.mikoto2000.rei.application.task;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
class ShellTaskTrackingTest {
  @TempDir Path root;
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"true,false","false,true"})
  void taskTrackingRegistersShellRunsWithoutEnablingConversationMode(boolean tasksEnabled,boolean todayEnabled) {
    var projects=new ProjectService(root,new ProjectRegistry(root.resolve("projects.json")));
    var registry=new RunRegistry(Clock.systemUTC());var jobs=new ArrayList<Runnable>();
    var router=new ConversationInputRouter(jobs::add,(c,p,q)->{});
    var service=new RunService(registry,new InMemoryAgentEventBus(),new AgentEventFactory(Clock.systemUTC()),new CommandCancellationService(),router::cancelQueued);
    var beans=new DefaultListableBeanFactory();beans.registerSingleton("runs",registry);beans.registerSingleton("service",service);
    var shell=new AgentRunConfiguration().shellConversations(projects,new SessionLifecycle(new FileSessionRepository(root.resolve("sessions.json")),Clock.systemUTC()),router,false,tasksEnabled,todayEnabled,beans.getBeanProvider(RunRegistry.class),beans.getBeanProvider(RunService.class));
    try(var scope=ProjectClientScope.open(projects.newClient())) {
      var run=shell.submit("work");assertThat(registry.get(run.runId()).status()).isEqualTo(RunStatus.QUEUED);
      assertThatThrownBy(()->shell.submit("consult",AgentRunContext.Mode.CONVERSATION)).isInstanceOf(IllegalArgumentException.class);
      assertThat(registry.runIds()).containsExactly(run.runId());
    }
  }
}
