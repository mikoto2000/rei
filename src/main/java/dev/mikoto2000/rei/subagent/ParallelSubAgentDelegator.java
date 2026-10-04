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
  public record Request(String id, String agent, String task, String context) { }
  public enum Status { COMPLETED, PARTIAL, FAILED, CANCELLED, TIMEOUT, REJECTED }
  public record Item(String id, String agent, Status status, SubAgentResult result) { }
  public record Batch(Status status, List<Item> items) { public Batch { items=List.copyOf(items); } }
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
    var parent=AgentRunScope.current();
    if(parent!=null && parent.conversationId().startsWith("subagent:"))throw new IllegalArgumentException("Recursive delegation is prohibited");
    if(input==null || input.isEmpty() || input.size()>8)throw new IllegalArgumentException("Expected 1 to 8 delegation requests");
    var requests=new ArrayList<>(input);var ids=new HashSet<String>();
    for(var request:requests) {
      if(request==null || request.id()==null || !request.id().matches("[A-Za-z0-9_-]{1,64}") || !ids.add(request.id())
          || request.agent()==null || registry.findById(request.agent()).isEmpty() || request.task()==null || request.task().isBlank()
          || request.task().length()>16384 || (request.context()!=null && request.context().length()>32768))
        throw new IllegalArgumentException("Invalid delegation request or unavailable agent");
    }
    if(!admission.tryAcquire())return new Batch(Status.REJECTED,requests.stream().map(r->item(r,Status.REJECTED,null)).toList());
    var stopped=new AtomicBoolean(Thread.currentThread().isInterrupted());
    var deadlineReached=new AtomicBoolean();
    var futures=new CopyOnWriteArrayList<FutureTask<Item>>();
    Runnable cancel=()->{stopped.set(true);for(var future:futures)future.cancel(true);executor.purge();};
    var registration=cancellation.onCancel(parent==null?null:parent.runId(),cancel);
    long deadline=System.nanoTime()+timeout.toNanos();Status terminal=null;
    try {
      for(var request:requests) {
        var future=new FutureTask<Item>(()->{
          if(stopped.get() || Thread.currentThread().isInterrupted())return item(request,Status.CANCELLED,null);
          if(System.nanoTime()-deadline>=0){deadlineReached.set(true);return item(request,Status.TIMEOUT,null);}
          try(var scope=AgentRunScope.open(parent)) {
            var result=reservation==null?runner.run(request.agent(),request.task(),request.context()):runner.run(request.agent(),request.task(),request.context(),reservation);
            var state=switch(result.status()) {
              case COMPLETED -> Status.COMPLETED;case CANCELLED -> Status.CANCELLED;case TIMEOUT -> Status.TIMEOUT;default -> Status.FAILED;
            };
            return item(request,state,result);
          }
        });
        futures.add(future);
        if(stopped.get())future.cancel(true);
        else try{executor.execute(future);}catch(RejectedExecutionException rejected){cancel.run();terminal=Status.REJECTED;}
      }
      for(var future:futures) {
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
      return new Batch(terminal,results);
    }finally{cancel.run();registration.dispose();admission.release();}
  }
  private static Item item(Request request,Status status,SubAgentResult result){return new Item(request.id(),request.agent(),status,result);}
  @Override public void close(){for(var pending:executor.shutdownNow())if(pending instanceof Future<?> future)future.cancel(true);}
}
