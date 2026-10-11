package dev.mikoto2000.rei.subagent;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import org.springframework.stereotype.Component;

/** One admitted batch, bounded global workers and queue; each child uses the existing runner. */
@Component
public final class ParallelSubAgentDelegator implements AutoCloseable {
  private SubAgentProperties standaloneBudgetProperties=new SubAgentProperties();
  @org.springframework.beans.factory.annotation.Autowired
  public void setStandaloneBudgetProperties(SubAgentProperties properties){standaloneBudgetProperties=properties;}
  public record Request(String id, String agent, String task, String context, String backendId,
      String accountRef,String modelId,List<String> sourceRefs,String expectedOutput) {
    public Request(String id,String agent,String task,String context){this(id,agent,task,context,null,null,null,null,null);}
    public Request {sourceRefs=sourceRefs==null?null:List.copyOf(sourceRefs);}
  }
  public enum Status { COMPLETED, PARTIAL, FAILED, CANCELLED, TIMEOUT, REJECTED }
  public record Item(String id, String agent, Status status, SubAgentResult result,ChatGptSubAgentBackend.Receipt receipt) {
    public Item(String id,String agent,Status status,SubAgentResult result){this(id,agent,status,result,null);}
  }
  public record Batch(Status status, List<Item> items,PlanReviewComparison.Comparison comparison) {
    public Batch(Status status,List<Item> items){this(status,items,null);}
    public Batch { items=List.copyOf(items); }
  }
  private final SubAgentRunner runner;
  private final SubAgentRegistry registry;
  private final CommandCancellationService cancellation;
  private final Duration timeout;
  private final Semaphore admission=new Semaphore(1);
  private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{
    var thread=new Thread(r,"parallel-subagent");thread.setDaemon(true);return thread;
  });
  @org.springframework.beans.factory.annotation.Autowired
  public ParallelSubAgentDelegator(SubAgentRunner runner,SubAgentRegistry registry,CommandCancellationService cancellation) {
    this(runner,registry,cancellation,Duration.ofSeconds(120));
  }
  ParallelSubAgentDelegator(SubAgentRunner runner,SubAgentRegistry registry,CommandCancellationService cancellation,Duration timeout) {
    this.runner=runner;this.registry=registry;this.cancellation=cancellation;this.timeout=timeout;
  }
  public Batch delegate(List<Request> input) {return delegate(input,null);}
  public Batch delegate(List<Request> input,dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation) {
    return delegate(input,reservation,null,timeout);
  }
  Batch delegate(List<Request> input,dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation,
      java.util.function.BiFunction<Request,dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation,SubAgentResult> child,Duration deadlineLimit) {
    return delegate(input,reservation,child,deadlineLimit,false);
  }
  Batch delegate(List<Request> input,dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation,
      java.util.function.BiFunction<Request,dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation,SubAgentResult> child,Duration deadlineLimit,boolean failFast) {
    if(deadlineLimit==null || deadlineLimit.isNegative() || deadlineLimit.isZero() || deadlineLimit.compareTo(timeout)>0)throw new IllegalArgumentException("Invalid DAG wave deadline");
    var parent=AgentRunScope.current();
    if(parent!=null && parent.conversationId().startsWith("subagent:"))throw new IllegalArgumentException("Recursive delegation is prohibited");
    if(input==null || input.isEmpty() || input.size()>8)throw new IllegalArgumentException("Expected 1 to 8 delegation requests");
    var requests=new ArrayList<>(input);var ids=new HashSet<String>();
    boolean external=requests.stream().anyMatch(r->r!=null&&external(r.backendId()));
    for(var request:requests) {
      if(request==null || request.id()==null || !request.id().matches("[A-Za-z0-9_-]{1,64}") || !ids.add(request.id())
          || request.agent()==null || registry.findById(request.agent()).isEmpty() || request.task()==null || request.task().isBlank()
          || request.task().length()>16384 || (request.context()!=null && request.context().length()>32768))
        throw new IllegalArgumentException("Invalid delegation request or unavailable agent");
      if(request.backendId()!=null && !Set.of("local","chatgpt-plan","codex-app-server").contains(request.backendId()))
        throw new IllegalArgumentException("Unsupported delegation backend");
      if(external(request.backendId()) && (parent==null||reservation==null||request.context()!=null))
        throw new IllegalArgumentException("Explicit human Run, shared budget and sources required");
      if(!external(request.backendId()) && (request.accountRef()!=null||request.modelId()!=null||request.sourceRefs()!=null||request.expectedOutput()!=null))
        throw new IllegalArgumentException("External options require explicit backend");
    }
    if(!admission.tryAcquire())return new Batch(Status.REJECTED,requests.stream().map(r->item(r,Status.REJECTED,null)).toList());
    var effectiveReservation=reservation==null&&parent==null?StandaloneSubAgentBudget.create(standaloneBudgetProperties):reservation;
    var stopped=new AtomicBoolean(Thread.currentThread().isInterrupted());
    var deadlineReached=new AtomicBoolean();
    var futures=new CopyOnWriteArrayList<FutureTask<Item>>();
    Runnable cancel=()->{stopped.set(true);for(var future:futures)future.cancel(true);executor.purge();};
    var registration=cancellation.onCancel(parent==null?null:parent.runId(),cancel);
    long deadline=System.nanoTime()+deadlineLimit.toNanos();Status terminal=null;
    try {
      for(var request:requests) {
        var future=new FutureTask<Item>(()->{
          if(stopped.get() || Thread.currentThread().isInterrupted())return item(request,Status.CANCELLED,null);
          if(System.nanoTime()-deadline>=0){deadlineReached.set(true);return item(request,Status.TIMEOUT,null);}
          try(var scope=AgentRunScope.open(parent)) {
            ChatGptSubAgentBackend.Receipt receipt=null;
            SubAgentResult result;
            if(child==null && external(request.backendId())) {
              receipt=runner.runAutomaticBackend(new ChatGptSubAgentBackend.Request(request.agent(),request.backendId(),request.modelId(),request.accountRef(),request.task(),request.sourceRefs()==null?List.of():request.sourceRefs(),request.expectedOutput()),parent,effectiveReservation);
              result=receipt.result();
            } else result=child!=null?child.apply(request,effectiveReservation):effectiveReservation==null?runner.run(request.agent(),request.task(),request.context()):runner.run(request.agent(),request.task(),request.context(),effectiveReservation);
            var state=switch(result.status()) {
              case COMPLETED -> Status.COMPLETED;case CANCELLED -> Status.CANCELLED;case TIMEOUT -> Status.TIMEOUT;default -> Status.FAILED;
            };
            if(external && result.structuredOutput()!=null && result.structuredOutput().status()==SubAgentOutput.Status.PARTIAL)state=Status.PARTIAL;
            return new Item(request.id(),request.agent(),state,result,receipt);
          }
        });
        futures.add(future);
        if(stopped.get())future.cancel(true);
        else try{executor.execute(future);}catch(RejectedExecutionException rejected){cancel.run();terminal=Status.REJECTED;}
      }
      for(var future:futures) {
        if(failFast) {
          while(!future.isDone() && !stopped.get() && System.nanoTime()<deadline) {
            boolean failed=false;
            for(var candidate:futures)if(candidate.isDone() && !candidate.isCancelled()) {
              try {var item=candidate.get();if(item.status()!=Status.COMPLETED || item.result()!=null && item.result().structuredOutput()!=null && item.result().structuredOutput().status()!=SubAgentOutput.Status.SUCCESS)failed=true;}
              catch(ExecutionException | CancellationException error){failed=true;}
              catch(InterruptedException error){Thread.currentThread().interrupt();terminal=Status.CANCELLED;failed=true;}
            }
            if(failed){if(terminal==null)terminal=Status.FAILED;cancel.run();break;}
            try{Thread.sleep(10);}catch(InterruptedException error){Thread.currentThread().interrupt();terminal=Status.CANCELLED;cancel.run();break;}
          }
        }
        if(stopped.get()) {if(terminal==null)terminal=Status.CANCELLED;break;}
        if(System.nanoTime()-deadline>=0){terminal=Status.TIMEOUT;cancel.run();break;}
        try {future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(TimeoutException error){terminal=Status.TIMEOUT;cancel.run();break;}
        catch(InterruptedException error){terminal=Status.CANCELLED;cancel.run();Thread.currentThread().interrupt();break;}
        catch(CancellationException error){terminal=Status.CANCELLED;cancel.run();break;}
        catch(ExecutionException error){/* Preserve sibling results; diagnostics must not echo exception text. */}
      }
      var results=new ArrayList<Item>();
      for(int i=0;i<futures.size();i++) {
        var future=futures.get(i);var request=requests.get(i);
        try{results.add(future.get());}
        catch(CancellationException error){results.add(item(request,Status.CANCELLED,null));}
        catch(InterruptedException error){Thread.currentThread().interrupt();results.add(item(request,Status.CANCELLED,null));}
        catch(ExecutionException error){results.add(item(request,Status.FAILED,null));}
      }
      if(terminal==null && deadlineReached.get())terminal=Status.TIMEOUT;
      if(terminal==null) {
        long completed=results.stream().filter(r->r.status()==Status.COMPLETED).count();
        terminal=completed==results.size()?Status.COMPLETED:completed>0?Status.PARTIAL:Status.FAILED;
      }
      var comparison=external && requests.stream().map(Request::task).distinct().count()==1?PlanReviewComparison.compare(results):null;
      return new Batch(terminal,results,comparison);
    }finally{cancel.run();registration.dispose();admission.release();}
  }
  private static boolean external(String backend){return "chatgpt-plan".equals(backend)||"codex-app-server".equals(backend);}
  private static Item item(Request request,Status status,SubAgentResult result){return new Item(request.id(),request.agent(),status,result);}
  @Override public void close(){for(var pending:executor.shutdownNow())if(pending instanceof Future<?> future)future.cancel(true);}
}
