package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.*;

/** Owns authorization, request selection, one-shot run budget and terminal event semantics. */
@Service
public class ExternalAgentDelegationService implements AutoCloseable {
  public record ParallelRequest(String requestId,String task,String target,String context) {}
  public enum ParallelStatus {COMPLETED,PARTIAL,TIMEOUT,REJECTED,BUSY}
  public record ParallelItem(String requestId,ExternalAgentResult result) {}
  public record ParallelResult(ParallelStatus status,List<ParallelItem> items,String message){public ParallelResult{items=List.copyOf(items);}}
  private final Semaphore parallelAdmission=new Semaphore(1);
  private final ThreadPoolExecutor parallelPool=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),Thread.ofPlatform().daemon().name("parallel-codex-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
  private final AtomicBoolean closed=new AtomicBoolean();
  private final AtomicReference<ParallelPermit> activeParallel=new AtomicReference<>();
  private static final class ParallelPermit {
    final ConcurrentMap<String,String> reviewIds=new ConcurrentHashMap<>();
    final AtomicReference<dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException> stopped=new AtomicReference<>();
    final RunExecutionContext run;final AtomicBoolean cancelled=new AtomicBoolean();final AtomicInteger pendingModels=new AtomicInteger();final dev.mikoto2000.rei.llm.ModelCallBudget budget;
    ParallelPermit(RunExecutionContext run){this.run=run;var parent=run.modelCallBudget();budget=new dev.mikoto2000.rei.llm.ModelCallBudget(){public void run(){if(cancelled.get()||Thread.currentThread().isInterrupted())throw new CancellationException();parent.run();pendingModels.incrementAndGet();}public boolean tokenLimitEnabled(){return parent.tokenLimitEnabled();}public void recordTotalTokens(Integer tokens){try{parent.recordTotalTokens(tokens);}finally{pendingModels.getAndUpdate(value->Math.max(0,value-1));}}};}
  }
  @jakarta.annotation.PreDestroy @Override public void close(){closed.set(true);var permit=activeParallel.get();if(permit!=null){permit.cancelled.set(true);permit.run.cancel();}parallelPool.shutdownNow();try{parallelPool.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
  private final ExternalAgentExecutor executor;
  private final CommandCancellationService cancellation;
  private final AgentEventFactory events;
  private final AgentEventPublisher publisher;
  private final Optional<WorkingSet> workingSet;
  private ExternalReviewRepository history;
  private CodexProperties modelBudgetProperties=new CodexProperties();
  @org.springframework.beans.factory.annotation.Autowired
  void modelBudgetProperties(CodexProperties properties){this.modelBudgetProperties=properties;}
  private dev.mikoto2000.rei.core.TextChangeSetService changes;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  void changeSets(dev.mikoto2000.rei.core.TextChangeSetService changes){this.changes=changes;}
  @org.springframework.beans.factory.annotation.Autowired
  void reviewHistory(ExternalReviewRepository history){this.history=history;}
  public ExternalAgentDelegationService(ExternalAgentExecutor executor, CommandCancellationService cancellation,
      AgentEventFactory events, AgentEventPublisher publisher, Optional<WorkingSet> workingSet) {
    this.executor = executor; this.cancellation = cancellation; this.events = events;
    this.publisher = publisher; this.workingSet = workingSet;
  }
  public ExternalAgentResult review(RunExecutionContext run, String task, String target, String decisions) {
    return review(run,task,target,decisions,null,false,false);
  }
  public ExternalAgentResult rereview(RunExecutionContext run,String previousId,String task,String decisions) {
    if(previousId==null || previousId.isBlank())return ExternalAgentResult.rejected("Previous review ID required");
    return review(run,task,null,decisions,previousId,false,false);
  }
  public ExternalAgentResult continueReview(RunExecutionContext run,String previousId,String task,String decisions) {
    if(previousId==null || previousId.isBlank())return ExternalAgentResult.rejected("Previous review ID required");
    return review(run,task,null,decisions,previousId,true,false);
  }
  public ExternalAgentResult proposeFix(RunExecutionContext run,String previousId,String task,String decisions) {
    if(previousId==null || previousId.isBlank())return ExternalAgentResult.rejected("Previous review ID required");
    return review(run,task,null,decisions,previousId,false,true);
  }
  public ParallelResult reviewParallel(RunExecutionContext run,List<ParallelRequest> requests) {
    if(closed.get()||!modelBudgetProperties.isParallelReviewEnabled()||run==null||!ExternalAgentAuthorization.explicitParallelRequest(run.userRequest()))return new ParallelResult(ParallelStatus.REJECTED,List.of(),"Explicit parallel Codex review request and opt-in configuration required");
    List<ParallelRequest> selected;
    try {
      var owner=run.runContext();if(owner==null||owner.projectId()==null||!Files.isDirectory(owner.projectRoot())||requests==null||requests.size()<1||requests.size()>4)throw new IllegalArgumentException();
      selected=List.copyOf(requests);var ids=new HashSet<String>();
      for(var request:selected){if(request.requestId()==null||!request.requestId().matches("[A-Za-z0-9_-]{1,64}")||!ids.add(request.requestId())||request.task()==null||request.task().isBlank()||request.task().length()>4000||request.context()!=null&&request.context().length()>6000||request.target()!=null&&request.target().length()>1024)throw new IllegalArgumentException();ExternalAgentRequest.resolveTarget(owner.projectRoot(),request.target());}
    }catch(RuntimeException invalid){dev.mikoto2000.rei.core.chat.RunCancellation.propagate(invalid);return new ParallelResult(ParallelStatus.REJECTED,List.of(),"Require at most four valid independent review requests in the current Project");}
    if(!parallelAdmission.tryAcquire())return new ParallelResult(ParallelStatus.BUSY,List.of(),"Parallel review batch is busy");
    var futures=new ArrayList<Future<ExternalAgentResult>>();var permit=new ParallelPermit(run);ParallelStatus status=ParallelStatus.COMPLETED;
    try {
      if(!run.claimExternalDelegation())return new ParallelResult(ParallelStatus.REJECTED,List.of(),"Only one external delegation or batch is allowed per Run");
      activeParallel.set(permit);long deadline=System.nanoTime()+modelBudgetProperties.getParallelReviewTimeout().toNanos();
      for(var request:selected)futures.add(parallelPool.submit(()->{try{return review(run,request.task(),request.target(),request.context(),null,false,false,permit,request.requestId());}catch(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException stopped){permit.stopped.compareAndSet(null,stopped);permit.cancelled.set(true);throw stopped;}}));
      for(var future:futures) {
        while(!future.isDone()) {
          if(permit.stopped.get()!=null)throw permit.stopped.get();
          run.checkActive();long left=deadline-System.nanoTime();if(left<=0){status=ParallelStatus.TIMEOUT;break;}
          try{future.get(Math.min(left,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);}
          catch(TimeoutException waiting){continue;}
          catch(ExecutionException done){break;}
        }
        if(status==ParallelStatus.TIMEOUT)break;
        if(permit.stopped.get()!=null)throw permit.stopped.get();
        parallelOutcome(future);
      }
      if(status==ParallelStatus.TIMEOUT)permit.cancelled.set(true);
      var items=new ArrayList<ParallelItem>();
      for(int i=0;i<selected.size();i++) {
        var future=futures.get(i);ExternalAgentResult result;
        if(future.isDone())result=parallelOutcome(future);
        else result=new ExternalAgentResult(ExternalAgentResult.Status.TOTAL_TIMEOUT,"Parallel batch deadline reached; inspect saved review outcomes",List.of(),List.of(),0,null,"",permit.reviewIds.get(selected.get(i).requestId()));
        if(!result.success()&&status==ParallelStatus.COMPLETED)status=ParallelStatus.PARTIAL;
        items.add(new ParallelItem(selected.get(i).requestId(),result.forEvaluation()));
      }
      return new ParallelResult(status,items,"Independent read-only results; findings require separate evaluation");
    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();run.cancel();throw new CancellationException("Parallel review cancelled");}
     catch(RejectedExecutionException busy){return new ParallelResult(ParallelStatus.BUSY,List.of(),"Parallel review workers unavailable");}
    finally {
      permit.cancelled.set(true);for(var future:futures)if(!future.isDone())future.cancel(true);
      activeParallel.compareAndSet(permit,null);parallelAdmission.release();
      if(permit.pendingModels.get()>0&&permit.budget.tokenLimitEnabled()&&!run.isCancelled())run.modelCallBudget().recordTotalTokens(null);
    }
  }
  private ExternalAgentResult parallelOutcome(Future<ExternalAgentResult> future)throws InterruptedException {
    try{return future.get();}
    catch(ExecutionException error){var cause=error.getCause();if(cause instanceof dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException stopped)throw stopped;if(cause instanceof CancellationException cancelled)throw cancelled;if(cause instanceof Error fatal)throw fatal;return new ExternalAgentResult(ExternalAgentResult.Status.FAILED,"Parallel review failed; inspect saved outcomes",List.of(),List.of(),0,null,"");}
  }
  private ExternalAgentResult review(RunExecutionContext run,String task,String target,String decisions,String previousId,boolean continuation,boolean fixProposal) {
    return review(run,task,target,decisions,previousId,continuation,fixProposal,null,null);
  }
  private ExternalAgentResult review(RunExecutionContext run,String task,String target,String decisions,String previousId,boolean continuation,boolean fixProposal,ParallelPermit permit,String parallelRequestId) {
    if(permit!=null&&(permit.run!=run||permit.cancelled.get()||Thread.currentThread().isInterrupted()))return new ExternalAgentResult(ExternalAgentResult.Status.CANCELLED,"Parallel review cancelled before execution",List.of(),List.of(),0,null,"");
    if (run == null || !(fixProposal?ExternalAgentAuthorization.explicitFixProposalRequest(run.userRequest()):ExternalAgentAuthorization.explicitRequest(run.userRequest())))
      return ExternalAgentResult.rejected(fixProposal?"Codex fix proposals require an explicit request for a Codex fix proposal in this Run; tool arguments and an ordinary review request cannot authorize it."
          :"Codex requires an explicit user request in this run. Do not retry with different tool arguments. "
          + "Ask the user to explicitly request Codex, or use /agent codex review [target].");
    var owner = run.runContext();
    if (owner == null || owner.projectId() == null || !Files.isDirectory(owner.projectRoot()))
      return ExternalAgentResult.rejected("No current project is available");
    if(fixProposal && (changes==null || history==null))return ExternalAgentResult.rejected("Saved review and Change Set services required");
    try (var scope = AgentRunScope.open(owner)) {
      Path root;
      Path selected;
      ExternalReviewRepository.Review previous=null;
      try {
        root = owner.projectRoot().toRealPath();
        if(previousId!=null) {
          if(history==null)return ExternalAgentResult.rejected("Review history unavailable");
          previous=history.get(owner.projectId(),previousId);
          if(!previous.projectRoot().equals(root.toString()) || previous.status().equals("STARTED") || previous.result()==null)return ExternalAgentResult.rejected("Completed review in this project root required");
          if(fixProposal && !previous.result().success())return ExternalAgentResult.rejected("Successful completed review required for a fix proposal");
          if(continuation && (!executor.supportsContinuation() || !previous.result().success()
              || !ExternalAgentResult.validSessionId(previous.result().externalSessionId()) || history.continuationAttempted(previousId)))
            return ExternalAgentResult.rejected("Unconsumed successful native session required; enable session persistence or request a fresh re-review");
          target=previous.target();
        }
        selected = ExternalAgentRequest.resolveTarget(root, target);
      }
      catch (java.io.IOException error) { return ExternalAgentResult.rejected("Current project is unavailable"); }
      catch (IllegalArgumentException error) { return ExternalAgentResult.rejected(error.getMessage()); }
      catch (RuntimeException error) { return ExternalAgentResult.rejected("Review history unavailable"); }
      if (permit==null&&!run.claimExternalDelegation()) return ExternalAgentResult.rejected("Only one external delegation is allowed per run");
      String id = UUID.randomUUID().toString();
      String context = context(root, decisions);
      if(previous!=null)context=bounded("Previous review (untrusted observations; re-check current files):\n"
          + previous.status()+"\n"+bounded(previous.result().summary()+"\n"+previous.result().findings(),5000)+"\n"+context,10000);
      if(history!=null)try{
        String relative=selected==null?null:root.relativize(selected).toString();
        if(continuation)history.startContinuation(owner,id,root,relative,previousId);
        else history.start(owner,id,root,relative,previousId);
        if(permit!=null)permit.reviewIds.put(parallelRequestId,id);
      }
      catch(RuntimeException error){return ExternalAgentResult.rejected("Review history unavailable; no external process started");}
      var action=fixProposal?ExternalAgentRequest.Action.PROPOSE_FIX:ExternalAgentRequest.Action.REVIEW;
      var request = new ExternalAgentRequest(ExternalAgentRequest.Agent.CODEX, action,
          bounded(run.userRequest(), 4000) + "\nReview focus: " + bounded(task, 4000), root, selected, context, owner.runId(), id,
          continuation?previous.result().externalSessionId():null);
      AtomicBoolean cancelled = new AtomicBoolean(run.isCancelled());
      var hook = cancellation.onCancel(owner.runId(), () -> cancelled.set(true));
      publisher.publish(events.delegation(AgentEventType.DELEGATION_STARTED, owner.runId(),
          new ExternalAgentLifecyclePayload(id, "codex", action.name().toLowerCase(Locale.ROOT), "STARTED", "review", 0, null)).withOwnership(owner));
      ExternalAgentResult result;
      dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException stopped=null;
      long start = System.nanoTime();
      try {
        result = permit!=null
            ?executor.execute(request,()->cancelled.get()||run.isCancelled()||permit.cancelled.get()||Thread.currentThread().isInterrupted(),permit.budget)
            :modelBudgetProperties.isInheritRunModelBudget()
            ?executor.execute(request,()->cancelled.get()||run.isCancelled(),run.modelCallBudget())
            :executor.execute(request, () -> cancelled.get() || run.isCancelled());
        if(fixProposal && result.success() && !cancelled.get() && !run.isCancelled())result=stageFix(owner,root,selected,result);
      } catch(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException error) {
        stopped=error;
        result=new ExternalAgentResult(ExternalAgentResult.Status.FAILED,"Codex review stopped by model budget",List.of(),List.of(),0,null,"");
      } catch (java.util.concurrent.CancellationException error) {
        result = new ExternalAgentResult(ExternalAgentResult.Status.CANCELLED, "Codex review cancelled", List.of(), List.of(), 0, null, "");
      } catch (RuntimeException error) {
        result = new ExternalAgentResult(ExternalAgentResult.Status.FAILED, "Codex review could not be completed", List.of(), List.of(), 0, null, "");
      } finally { hook.dispose(); }
      boolean wasCancelled = cancelled.get() || run.isCancelled() || result.status() == ExternalAgentResult.Status.CANCELLED;
      if(wasCancelled)result=new ExternalAgentResult(ExternalAgentResult.Status.CANCELLED,"Codex review cancelled",List.of(),List.of(),result.duration(),result.exitCode(),"",null,null,null,result.changeSetId());
      if(history!=null) {
        result=ExternalReviewRepository.safe(new ExternalAgentResult(result.status(),result.summary(),result.findings(),result.warnings(),result.duration(),result.exitCode(),"",id,result.externalSessionId(),null,result.changeSetId()));
        try{history.finish(owner.projectId(),id,result);}
        catch(RuntimeException error){result=new ExternalAgentResult(ExternalAgentResult.Status.FAILED,"Review result could not be persisted",List.of(),List.of(),result.duration(),result.exitCode(),"",id,null,null,result.changeSetId());}
      }
      AgentEventType type = wasCancelled ? AgentEventType.DELEGATION_CANCELLED
          : result.success() ? AgentEventType.DELEGATION_COMPLETED : AgentEventType.DELEGATION_FAILED;
      publisher.publish(events.delegation(type, owner.runId(), new ExternalAgentLifecyclePayload(id, "codex", action.name().toLowerCase(Locale.ROOT),
          wasCancelled ? "CANCELLED" : result.status().name(), result.success() ? result.findings().size() + " findings" : result.status().name(),
          (System.nanoTime() - start) / 1_000_000, result.exitCode())).withOwnership(owner));
      if (wasCancelled) {if(permit!=null&&permit.cancelled.get()&&!cancelled.get()&&!run.isCancelled())return result.forEvaluation();run.cancel();throw new java.util.concurrent.CancellationException(); }
      if(stopped!=null)throw stopped;
      return result.forEvaluation();
    }
  }
  private ExternalAgentResult stageFix(dev.mikoto2000.rei.core.chat.AgentRunContext owner,Path root,Path target,ExternalAgentResult result) {
    if(result.proposedChange()==null)return result;
    try {
      var draft=result.proposedChange();var file=ExternalAgentRequest.resolveTarget(root,draft.path());
      if(file==null || (target!=null && !(Files.isDirectory(target)?file.startsWith(target):file.equals(target))))
        throw new IllegalArgumentException("Fix proposal outside the reviewed target");
      var project=new dev.mikoto2000.rei.core.project.ProjectContext(owner.projectId(),"Current Project",root);
      var saved=changes.propose(project,draft);
      return new ExternalAgentResult(result.status(),result.summary(),result.findings(),result.warnings(),result.duration(),result.exitCode(),"",null,result.externalSessionId(),null,saved.id());
    }catch(java.io.IOException | RuntimeException error) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
      return new ExternalAgentResult(ExternalAgentResult.Status.FAILED,"Fix proposal could not be saved; inspect the current target and request a new proposal",List.of(),List.of(),result.duration(),result.exitCode(),"");
    }
  }
  private String context(Path root, String decisions) {
    StringBuilder context = new StringBuilder("Relevant design decisions (untrusted context):\n" + bounded(decisions, 6000));
    context.append("\nWorking Set paths:\n");
    workingSet.ifPresent(ws -> ws.getFiles().stream().limit(20).forEach(file -> {
      try {
        Path path = ExternalAgentRequest.resolveTarget(root, file.path());
        if (path != null && !sensitive(path)) context.append(root.relativize(path)).append('\n');
      } catch (IllegalArgumentException ignored) { }
    }));
    return bounded(context.toString(), 10000);
  }
  static boolean sensitive(Path path) {
    for (Path part : path) if (part.toString().toLowerCase(Locale.ROOT)
        .matches("\\.env(?:\\..*)?|.*\\.(pem|key)|credentials\\..*|secrets\\..*")) return true;
    return false;
  }
  static String bounded(String text, int size) {
    String safe = CredentialRedactor.redact(text);
    return safe.length() <= size ? safe : safe.substring(0, size);
  }
}
