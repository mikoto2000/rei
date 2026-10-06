package dev.mikoto2000.rei.core;

import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.chat.model.ToolContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.dependency.*;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.goal.*;

/** Read-only file adapter sharing independent Goal verification and bounded waiting. */
@Component
public class FileDependencyTools {
  private final FileGoalVerifier verifier;
  private DependencyAwaiter awaiter=new DependencyAwaiter(dev.mikoto2000.rei.temporal.MonotonicTimeSource.system(),Thread::sleep);
  public FileDependencyTools(FileGoalVerifier verifier){this.verifier=verifier;}
  void setDependencyAwaiter(DependencyAwaiter awaiter){this.awaiter=awaiter;}
  @Tool(name="waitForFile",description="Wait up to 60 seconds (default 10) for a regular file inside the captured Project. Optional expectedSha256 requires exact content digest; absent digest requires existence. File size limit 1 MiB; symlinks/traversal rejected. WAITING at timeout is not completion. Reads no file contents into the model and never writes.")
  public DependencyObservation waitForFile(String relativeFile,@org.springframework.ai.tool.annotation.ToolParam(required=false) String expectedSha256,@org.springframework.ai.tool.annotation.ToolParam(required=false) Integer timeoutSeconds,ToolContext toolContext) {
    var owner=AgentRunScope.current();if(owner==null||owner.projectId()==null||owner.projectId().isBlank())throw new IllegalArgumentException("Owning project required");
    if(relativeFile==null||relativeFile.isBlank()||relativeFile.length()>1024)throw new IllegalArgumentException("Relative file is required (up to 1024 characters)");
    if(expectedSha256!=null&&!expectedSha256.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("Expected SHA-256 must have 64 hexadecimal characters");
    var criterion=new GoalRepository.FileCriterion(relativeFile,expectedSha256==null?null:expectedSha256.toLowerCase(java.util.Locale.ROOT));
    var execution=toolContext!=null&&toolContext.getContext().get(RunExecutionContext.KEY) instanceof RunExecutionContext current?current:null;
    var observation=awaiter.await(()->{
      var checked=verifier.verify(owner.projectRoot(),criterion);
      var state=checked.satisfied()?DependencyState.COMPLETED:
          java.util.Set.of("file_missing_or_not_regular","digest_mismatch").contains(checked.reason())?DependencyState.WAITING:DependencyState.BLOCKED;
      return new DependencyObservation("file:"+relativeFile,state,checked.reason());
    },Duration.ofSeconds(timeoutSeconds==null?10:timeoutSeconds),()->{if(execution!=null)execution.checkActive();});
    if(execution!=null)execution.observeDependency(observation);return observation;
  }
}
