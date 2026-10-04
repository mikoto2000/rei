package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.dependency.*;
import dev.mikoto2000.rei.core.process.*;
import dev.mikoto2000.rei.core.service.SystemShellService;

class ProcessDependencyToolsTest {
  @Test void callbackKeepsExecutionContextOutOfSchemaAndReportsVerifiedWaiting() {
    var manager=mock(BackgroundProcessManager.class);var tools=tools(manager);
    when(manager.status("p",null)).thenReturn(snapshot(BackgroundProcessStatus.RUNNING,null,true));
    var context=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("run",new dev.mikoto2000.rei.llm.OutputLimitRunBudget(1,10),
        new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(Path.of(".")),new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),event->{});
    var callback=org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks()[0];
    assertFalse(callback.getToolDefinition().inputSchema().contains("toolContext"));
    context.beginIteration();
    var before=context.evaluator().beforeTool("waitForShellProcess","{}");
    var result=callback.call("{\"processId\":\"p\",\"timeoutSeconds\":0}",new org.springframework.ai.chat.model.ToolContext(
        Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY,context)));
    assertTrue(result.contains("WAITING"));context.recordTool("waitForShellProcess","{}",result,before);context.endIteration();
    assertEquals(0,context.detector().stagnationCount());
  }
  BackgroundProcessSnapshot snapshot(BackgroundProcessStatus status,Integer code,boolean found) {
    return new BackgroundProcessSnapshot("p",42,status,code,Instant.EPOCH,null,1,List.of("log"),List.of(),found,"");
  }
  ProcessDependencyTools tools(BackgroundProcessManager manager) {
    var tools=new ProcessDependencyTools(manager);var time=new AtomicLong();
    tools.setDependencyAwaiter(new DependencyAwaiter(time::get,d->time.addAndGet(d.toNanos())));return tools;
  }
  @Test void processCompletionAndTimeoutAreDifferentAndNeverLaunchOrKill() {
    var manager=mock(BackgroundProcessManager.class);var tools=tools(manager);
    when(manager.status("p",null)).thenReturn(snapshot(BackgroundProcessStatus.RUNNING,null,true),snapshot(BackgroundProcessStatus.EXITED,0,true));
    var completed=tools.waitForShellProcess("p",10,null);
    assertEquals(DependencyState.COMPLETED,completed.dependency().state());assertEquals(List.of("log"),completed.process().stdout());
    when(manager.status("p",null)).thenReturn(snapshot(BackgroundProcessStatus.RUNNING,null,true));
    assertEquals(DependencyState.WAITING,tools.waitForShellProcess("p",1,null).dependency().state());
    verify(manager,never()).spawnShell(anyString(),any());verify(manager,never()).kill(anyString());
  }
  @Test void failuresCancellationAndUnknownProcessHaveExplicitStates() {
    var manager=mock(BackgroundProcessManager.class);var tools=tools(manager);
    when(manager.status("p",null)).thenReturn(snapshot(BackgroundProcessStatus.EXITED,1,true));
    assertEquals(DependencyState.FAILED,tools.waitForShellProcess("p",10,null).dependency().state());
    when(manager.status("p",null)).thenReturn(snapshot(BackgroundProcessStatus.KILLED,null,true));
    assertEquals(DependencyState.CANCELLED,tools.waitForShellProcess("p",10,null).dependency().state());
    when(manager.status("p",null)).thenReturn(snapshot(BackgroundProcessStatus.FAILED,null,false));
    assertEquals(DependencyState.BLOCKED,tools.waitForShellProcess("p",10,null).dependency().state());
    assertThrows(IllegalArgumentException.class,()->tools.waitForShellProcess("p",61,null));
  }
}
