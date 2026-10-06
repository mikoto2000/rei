package dev.mikoto2000.rei.core.dependency;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.annotation.*;
import org.springframework.ai.chat.model.ToolContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;

@Component
public class DependencyTools {
  private final PersistentDependencyRepository repo;private final DependencyObservationService service;private final DependencySourceProbe source;
  private final DependencyAwaiter httpAwaiter=new DependencyAwaiter(dev.mikoto2000.rei.temporal.MonotonicTimeSource.system(),Thread::sleep,Duration.ofSeconds(1));
  private final DependencyAwaiter awaiter=new DependencyAwaiter(dev.mikoto2000.rei.temporal.MonotonicTimeSource.system(),Thread::sleep);
  public DependencyTools(PersistentDependencyRepository repo,DependencyObservationService service,DependencySourceProbe source){this.repo=repo;this.service=service;this.source=source;}
  @Tool(name="registerDependency",description="Persist a scoped dependency: FILE_EXISTS, FILE_SHA256, FILE_CHANGED (baseline captured if absent), GIT_STATE_CHANGED (target HEAD, baseline captured if absent), PROCESS_EXIT (expected exit 0 by default), HTTP_STATUS (expected 100..599), HTTP_BODY_SHA256 (expected status:64-hex SHA-256, max body 64KiB), USER_ANSWER (target question). Optional ISO-8601 lifetime defaults PT1H, max 366 days; at most 16 existing same-session prerequisites. Automatic watching requires administrator enablement and auto permission; registration does not execute an action.")
  public PersistentDependencyRepository.Entry registerDependency(String kind,String target,@ToolParam(required=false) String expected,@ToolParam(required=false) String lifetime,@ToolParam(required=false) List<String> prerequisites) {
    var owner=AgentRunScope.current();if(owner==null||owner.projectId()==null)throw new IllegalArgumentException("Owning Project/Session required");
    if(kind==null||kind.isBlank())throw new IllegalArgumentException("Dependency kind required");
    var type=DependencySpec.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
    if(type==DependencySpec.Kind.GIT_STATE_CHANGED&&expected==null)expected=source.gitBaseline(owner.projectRoot());
    if(type==DependencySpec.Kind.FILE_CHANGED&&expected==null)expected=source.fileBaseline(owner.projectRoot(),target);
    var entry=repo.create(owner,new DependencySpec(type,target,expected),lifetime==null?Duration.ofHours(1):Duration.parse(lifetime),prerequisites);service.flushFacts();return entry;
  }
  private PersistentDependencyRepository.Entry owned(String id) {
    var owner=AgentRunScope.current();if(owner==null||owner.projectId()==null)throw new IllegalArgumentException("Owning Project/Session required");
    var entry=repo.get(owner.projectId(),id);
    if(!entry.sessionId().equals(owner.conversationId())||!entry.projectRoot().equals(owner.projectRoot().toString()))throw new IllegalArgumentException("Dependency belongs to another Session/location");return entry;
  }
  @Tool(name="dependencyStatus",description="Read a saved dependency state without observing external sources. Only current Project/Session.")
  public PersistentDependencyRepository.Entry dependencyStatus(String dependencyId){return owned(dependencyId);}
  @Tool(name="dependencyHistory",description="Read bounded saved observation facts for a dependency in the current Project/Session.")
  public List<PersistentDependencyRepository.Fact> dependencyHistory(String dependencyId){var entry=owned(dependencyId);return repo.history(entry.projectId(),entry.id());}
  @Tool(name="checkDependency",description="Observe a saved file/Git/managed-process/user-answer dependency once. No HTTP requests; use checkHttpDependency for HTTP.")
  public PersistentDependencyRepository.Entry checkDependency(String dependencyId){var entry=owned(dependencyId);return service.inspect(entry.projectId(),entry.id(),false);}
  @Tool(name="checkHttpDependency",description="Perform one bounded network observation of a saved HTTP_STATUS or HTTP_BODY_SHA256 dependency. No redirects, credentials or response-body exposure. Only current Project/Session.")
  public PersistentDependencyRepository.Entry checkHttpDependency(String dependencyId){var entry=owned(dependencyId);if(!entry.spec().network())throw new IllegalArgumentException("HTTP dependency required");return service.inspect(entry.projectId(),entry.id(),true);}
  @Tool(name="waitForDependency",description="Bounded wait (default 10s, max 60s) for a non-HTTP saved dependency. WAITING at timeout is not completion. Cancellation and waiting integrate with the current Run; does not launch/kill processes or answer questions. User-answer completion means a reply was received; read its saved answer before deciding what to do.")
  public DependencyObservation waitForDependency(String dependencyId,@ToolParam(required=false) Integer timeoutSeconds,ToolContext toolContext){return waitFor(dependencyId,timeoutSeconds,toolContext,false);}
  @Tool(name="waitForHttpDependency",description="Bounded network wait (default 10s, max 60s) for a saved HTTP_STATUS or HTTP_BODY_SHA256 dependency. Each request bounded to 2s; polls at least 1s apart, no redirects or body exposure. WAITING is not completion.")
  public DependencyObservation waitForHttpDependency(String dependencyId,@ToolParam(required=false) Integer timeoutSeconds,ToolContext toolContext){return waitFor(dependencyId,timeoutSeconds,toolContext,true);}
  private DependencyObservation waitFor(String id,Integer seconds,ToolContext context,boolean network) {
    var entry=owned(id);if(network!=(entry.spec().network()))throw new IllegalArgumentException("Use the matching network/non-network wait tool");
    var execution=context!=null&&context.getContext().get(RunExecutionContext.KEY) instanceof RunExecutionContext current?current:null;
    var observation=(network?httpAwaiter:awaiter).await(()->{var observed=service.inspect(entry.projectId(),entry.id(),network);return new DependencyObservation(observed.id(),observed.state(),observed.reason());},Duration.ofSeconds(seconds==null?10:seconds),()->{if(execution!=null)execution.checkActive();});
    if(execution!=null)execution.observeDependency(observation);return observation;
  }
  @Tool(name="cancelDependency",description="Cancel watching a saved dependency in the current Project/Session. Never kills its process or invokes an external action. Terminal observations cannot be overwritten.")
  public PersistentDependencyRepository.Entry cancelDependency(String dependencyId){var entry=owned(dependencyId);var cancelled=repo.cancel(entry.projectId(),entry.id());service.flushFacts();return cancelled;}
}
