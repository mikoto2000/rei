package dev.mikoto2000.rei.core;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.chat.model.ToolContext;
import dev.mikoto2000.rei.core.dependency.*;
import dev.mikoto2000.rei.core.process.*;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;

/** Managed-process adapter; waiting policy and time are kept in the domain awaiter. */
@Component
public class ProcessDependencyTools {
  private final BackgroundProcessManager processes;
  private DependencyAwaiter awaiter=new DependencyAwaiter(dev.mikoto2000.rei.temporal.MonotonicTimeSource.system(),Thread::sleep);
  public ProcessDependencyTools(BackgroundProcessManager processes){this.processes=processes;}
  void setDependencyAwaiter(DependencyAwaiter awaiter){this.awaiter=awaiter;}
  public record ShellDependencyResult(DependencyObservation dependency,BackgroundProcessSnapshot process) {}
  @Tool(name="waitForShellProcess",description="Wait up to 60 seconds for an existing managed shell process without launching or killing it. Default 10 seconds. Returns COMPLETED, FAILED, CANCELLED, BLOCKED (unknown process), or WAITING at timeout. WAITING is not successful completion. Uses bounded polling instead of repeated LLM status requests.")
  public ShellDependencyResult waitForShellProcess(String processId,Integer timeoutSeconds,ToolContext toolContext) {
    int seconds=timeoutSeconds==null?10:timeoutSeconds;
    var execution=toolContext!=null && toolContext.getContext().get(RunExecutionContext.KEY) instanceof RunExecutionContext current?current:null;
    var latest=new AtomicReference<BackgroundProcessSnapshot>();
    var result=awaiter.await(()->{
      var snapshot=processes.status(processId,null);latest.set(snapshot);
      var state=!snapshot.found()?DependencyState.BLOCKED:switch(snapshot.status()) {
        case STARTING,RUNNING -> DependencyState.RUNNING;
        case EXITED -> snapshot.exitCode()!=null && snapshot.exitCode()==0?DependencyState.COMPLETED:DependencyState.FAILED;
        case FAILED -> DependencyState.FAILED;
        case KILLED -> DependencyState.CANCELLED;
      };
      return new DependencyObservation(processId,state,"managed_shell_process");
    },Duration.ofSeconds(seconds),()->{if(execution!=null)execution.checkActive();});
    if(execution!=null)execution.observeDependency(result);
    return new ShellDependencyResult(result,latest.get());
  }
}
