package dev.mikoto2000.rei.reflection;
import org.springframework.stereotype.Service;
import jakarta.annotation.*;
import dev.mikoto2000.rei.event.*;
/** Event facts only: no additional model call and no inferred root causes. */
@Service
public class RunReflectionService {
  private final RunReflectionRepository repo;private final AgentEventBus bus;private final ProjectAgentEventStore history;private AgentEventBus.Subscription subscription;
  public RunReflectionService(RunReflectionRepository repo,AgentEventBus bus){this(repo,bus,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public RunReflectionService(RunReflectionRepository repo,AgentEventBus bus,ProjectAgentEventStore history){this.repo=repo;this.bus=bus;this.history=history;}
  /** Bounded human backfill of facts only. Never starts or verifies the saved Run. */
  public synchronized RunReflectionRepository.Item collect(String project,String session,String run) {
    if(history==null||!RunReflectionRepository.valid(project)||!RunReflectionRepository.valid(session)||!RunReflectionRepository.valid(run))
      throw new IllegalArgumentException("Project, Session, Run and persisted history required");
    var saved=history.recent(project,1000);
    if(saved.size()==1000)throw new IllegalArgumentException("History window is full; inspect the persisted event log instead of inferring a complete reflection");
    var owned=saved.stream().filter(e->project.equals(e.projectId())&&session.equals(e.sessionId())&&run.equals(e.runId())).toList();
    var terminal=owned.stream().filter(e->e.type()==AgentEventType.AGENT_RUN_COMPLETED&&e.payload() instanceof AgentRunCompletedPayload p&&matches(e,p.runId())
        ||(e.type()==AgentEventType.AGENT_RUN_FAILED||e.type()==AgentEventType.AGENT_RUN_CANCELLED)&&e.payload() instanceof AgentRunFailedPayload failed&&matches(e,failed.runId())).reduce((a,b)->b)
        .orElseThrow(()->new IllegalArgumentException("No owned terminal Run fact in the bounded history window"));
    for(var event:owned) {
      if(event.type()==AgentEventType.TOOL_COMPLETED||event.type()==AgentEventType.TOOL_FAILED)observe(event);
      if(event.id().equals(terminal.id()))break;
    }
    String status=terminal.type()==AgentEventType.AGENT_RUN_COMPLETED?"COMPLETED":terminal.type()==AgentEventType.AGENT_RUN_CANCELLED?"CANCELLED":"FAILED";
    return repo.reflect(terminal,"RUN",run,status,terminal.payload() instanceof AgentRunFailedPayload p?error(p.error()):"");
  }
  @PostConstruct public synchronized void start(){if(subscription==null)subscription=bus.subscribe(this::observe);}
  @PreDestroy public synchronized void close(){if(subscription!=null){subscription.unsubscribe();subscription=null;}}
  private synchronized void observe(AgentEvent event) {
    if(!RunReflectionRepository.valid(event.projectId())||!RunReflectionRepository.valid(event.sessionId()))return;
    switch(event.type()) {
      case TOOL_COMPLETED -> {if(event.payload() instanceof ToolCompletedPayload payload)repo.action(event,new RunReflectionRepository.Action(payload.toolCallId(),payload.toolName(),"COMPLETED",""));}
      case TOOL_FAILED -> {if(event.payload() instanceof ToolFailedPayload payload)repo.action(event,new RunReflectionRepository.Action(payload.toolCallId(),payload.toolName(),"FAILED",error(payload.error())));}
      case TASK_CREATED -> {if(event.payload() instanceof TaskCreatedPayload payload)repo.task(event,payload);}
      case TASK_COMPLETED -> {if(event.payload() instanceof TaskCompletedPayload payload&&RunReflectionRepository.valid(payload.taskId()))repo.reflect(event,"TASK",payload.taskId(),"COMPLETED","");}
      case TASK_FAILED -> {if(event.payload() instanceof TaskFailedPayload payload&&RunReflectionRepository.valid(payload.taskId()))repo.reflect(event,"TASK",payload.taskId(),"FAILED",error(payload.error()));}
      case AGENT_RUN_COMPLETED -> {if(event.payload() instanceof AgentRunCompletedPayload payload&&matches(event,payload.runId()))repo.reflect(event,"RUN",payload.runId(),"COMPLETED","");}
      case AGENT_RUN_FAILED,AGENT_RUN_CANCELLED -> {if(event.payload() instanceof AgentRunFailedPayload payload&&matches(event,payload.runId()))repo.reflect(event,"RUN",payload.runId(),event.type()==AgentEventType.AGENT_RUN_CANCELLED?"CANCELLED":"FAILED",error(payload.error()));}
      default -> { }
    }
  }
  private boolean matches(AgentEvent event,String id){return RunReflectionRepository.valid(id)&&id.equals(event.runId());}
  private String error(ErrorInformation error){return error==null?"not_recorded":RunReflectionRepository.safe(error.errorType(),128);}
}
