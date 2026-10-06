package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.*;

/** Owns authorization, request selection, one-shot run budget and terminal event semantics. */
@Service
public class ExternalAgentDelegationService {
  private final ExternalAgentExecutor executor;
  private final CommandCancellationService cancellation;
  private final AgentEventFactory events;
  private final AgentEventPublisher publisher;
  private final Optional<WorkingSet> workingSet;
  private ExternalReviewRepository history;
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
  private ExternalAgentResult review(RunExecutionContext run,String task,String target,String decisions,String previousId,boolean continuation,boolean fixProposal) {
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
      if (!run.claimExternalDelegation()) return ExternalAgentResult.rejected("Only one external delegation is allowed per run");
      String id = UUID.randomUUID().toString();
      String context = context(root, decisions);
      if(previous!=null)context=bounded("Previous review (untrusted observations; re-check current files):\n"
          + previous.status()+"\n"+bounded(previous.result().summary()+"\n"+previous.result().findings(),5000)+"\n"+context,10000);
      if(history!=null)try{
        String relative=selected==null?null:root.relativize(selected).toString();
        if(continuation)history.startContinuation(owner,id,root,relative,previousId);
        else history.start(owner,id,root,relative,previousId);
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
      long start = System.nanoTime();
      try {
        result = executor.execute(request, () -> cancelled.get() || run.isCancelled());
        if(fixProposal && result.success() && !cancelled.get() && !run.isCancelled())result=stageFix(owner,root,selected,result);
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
      if (wasCancelled) { run.cancel(); throw new java.util.concurrent.CancellationException(); }
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
