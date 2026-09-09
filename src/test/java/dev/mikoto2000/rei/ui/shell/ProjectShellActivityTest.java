package dev.mikoto2000.rei.ui.shell;

import java.nio.file.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemory;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectShellActivityTest {
  @Test void backgroundNotificationsRemainVisibleAfterProjectSwitch() throws Exception {
    var projects=new ProjectService(Files.createDirectory(temp.resolve("A")),new ProjectRegistry(temp.resolve("registry.json")));
    var a=projects.currentContext();
    var router=new ConversationInputRouter(new java.util.ArrayList<Runnable>()::add,(c,p,q)->{});
    var activity=new ProjectShellActivity(projects,new ProjectAgentEventStore(temp),new ProjectRunStateStore(temp),new WorkingSet(),mock(ChatMemory.class));
    activity.setActiveRuns(new ActiveRunDisplay(router,projects,Clock.systemUTC()));
    var out=mock(ShellEventOutput.class); activity.attach(out);
    projects.cd(Files.createDirectory(temp.resolve("B")).toString()); activity.restore(projects.currentContext());
    var events=new AgentEventFactory(Clock.systemUTC());
    for(var type:java.util.List.of(dev.mikoto2000.rei.core.execution.ExecutionType.SUMMARIZE,dev.mikoto2000.rei.core.execution.ExecutionType.IMAGE)) {
      var execution=new dev.mikoto2000.rei.core.execution.ActiveExecution("execution",a.id(),a.conversationId("chat:main"),a.root(),type,"request",java.time.Instant.now());
      activity.onEvent(events.backgroundExecution(execution,"COMPLETED","結果"));
      activity.onEvent(events.backgroundExecution(execution,"FAILED","失敗"));
      verify(out).println("["+type.name().toLowerCase()+".completed] A (execution)");
      verify(out).println("["+type.name().toLowerCase()+".failed] A (execution)");
    }
    verify(out,times(2)).println("結果");
  }
  @Test void projectSwitchShowsCountAndHiddenRunCompletionIsVisible() throws Exception {
    var projects = new ProjectService(Files.createDirectory(temp.resolve("a")), new ProjectRegistry(temp.resolve("registry.json")));
    var a = projects.currentContext();
    var tasks = new java.util.ArrayList<Runnable>();
    var runs = new ConversationInputRouter(tasks::add, (c,p,q) -> {});
    runs.submit(a.root(), a.conversationId("chat:main"), "work");
    var activity = new ProjectShellActivity(projects, new ProjectAgentEventStore(temp), new ProjectRunStateStore(temp), new WorkingSet(), mock(ChatMemory.class));
    activity.setActiveRuns(new ActiveRunDisplay(runs, projects, Clock.systemUTC()));
    var out = mock(ShellEventOutput.class); activity.attach(out);
    projects.cd(Files.createDirectory(temp.resolve("b")).toString());
    activity.restore(projects.currentContext());
    verify(out).println("Active runs: 1 (/runs for details)");
    activity.onEvent(new AgentEventFactory(Clock.systemUTC()).runCompleted("a", 1).withOwnership(new AgentRunContext("a", a, "chat:main")));
    verify(out).println("[agent.run.completed] a");
  }
  @TempDir Path temp;
  @Test void switchRestoresOnlyRecentOwnedActivityAndFiltersLiveEvents() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a")); Path b = Files.createDirectory(temp.resolve("b"));
    var projects = new ProjectService(a, new ProjectRegistry(temp.resolve("projects.json")));
    var runA = new AgentRunContext("run-a", projects.currentContext(), "chat:main");
    projects.cd(b.toString());
    var runB = new AgentRunContext("run-b", projects.currentContext(), "chat:main");
    var store = new ProjectAgentEventStore(temp);
    var factory = new AgentEventFactory(Clock.systemUTC());
    var eventA = factory.toolCompleted("tool-a", "read-A", 1, "ok").withOwnership(runA);
    var eventB = factory.toolCompleted("tool-b", "read-B", 1, "ok").withOwnership(runB);
    store.append(eventA); store.append(eventB);
    var activity = new ProjectShellActivity(projects, store, new ProjectRunStateStore(temp), new WorkingSet(), mock(ChatMemory.class));
    var text = new StringBuilder();
    activity.attach(new ShellEventOutput() {
      public void print(String value) { text.append(value); }
      public void println(String value) { text.append(value).append('\n'); }
      public void flush() {}
    });
    projects.cd(a.toString()); activity.restore(projects.currentContext());
    assertThat(text.toString()).contains("read-A", "Conversation restored: chat:main", "Working Set restored: 0").doesNotContain("read-B");
    text.setLength(0);
    projects.cd(b.toString()); activity.restore(projects.currentContext());
    assertThat(text.toString()).contains("read-B").doesNotContain("read-A");
    projects.cd(a.toString()); activity.restore(projects.currentContext());
    activity.onEvent(factory.messageStarted("message-a", "assistant").withOwnership(runA));
    projects.cd(b.toString()); activity.restore(projects.currentContext());
    text.setLength(0);
    activity.onEvent(factory.messageDelta("message-a", "hidden").withOwnership(runA));
    assertThat(text).isEmpty();
    projects.cd(a.toString()); activity.restore(projects.currentContext());
    text.setLength(0);
    activity.onEvent(factory.messageDelta("message-a", "visible continuation").withOwnership(runA));
    assertThat(text.toString()).contains("visible continuation");
    text.setLength(0);
    projects.cd(b.toString()); activity.restore(projects.currentContext()); text.setLength(0);
    activity.onEvent(eventA); activity.onEvent(eventB);
    assertThat(text.toString()).contains("read-B").doesNotContain("read-A");
  }
}
