package dev.mikoto2000.rei.goal;
import java.nio.file.Path;
import java.util.*;
/** Read-only completion view including optional checks and current content revisions. */
public record GoalCompletionProgress(String goalId,String runId,String state,
    FileGoalVerifier.Verification verification,List<Check> conditions,boolean deliveryPending,GoalRepairDiagnosis diagnosis) {
  public GoalCompletionProgress(String goalId,String runId,String state,FileGoalVerifier.Verification verification,List<Check> conditions,boolean deliveryPending) {
    this(goalId,runId,state,verification,conditions,deliveryPending,GoalRepairDiagnosis.of(verification==null?"":verification.reason()));
  }
  public GoalCompletionProgress { if(diagnosis==null)diagnosis=GoalRepairDiagnosis.of(verification==null?"":verification.reason()); }
  public record Check(String id,String statement,boolean required,boolean satisfied,String reason,String revision) {}
  static GoalCompletionProgress inspect(GoalRepository.Goal goal,String phase,FileGoalVerifier verifier){
    var checks=new ArrayList<Check>();Path root=Path.of(goal.projectRoot());
    for(int i=0;i<goal.criteria().size();i++)checks.add(check("file-"+i,"File criterion",true,root,goal.criteria().get(i),verifier));
    if(goal.completion()!=null){
      for(int i=0;i<goal.completion().completionEvidence().size();i++)checks.add(check("evidence-"+i,"Completion evidence",true,root,goal.completion().completionEvidence().get(i),verifier));
      for(int i=0;i<goal.completion().requiredPredicates().size();i++)checks.add(check("predicate-"+i,"Required predicate",true,root,goal.completion().requiredPredicates().get(i),verifier));
    }
    if(goal.completion()!=null)for(var requirement:goal.completion().requirements())checks.add(check("requirement-"+requirement.id(),requirement.statement(),requirement.required(),root,requirement.criterion(),verifier));
    var verified=verifier.verify(goal);boolean delivery=verified.reason().equals("completion_delivery_pending");
    String state=phase;
    if(delivery&&!Set.of("RUNNING","VERIFYING","REPAIRING","CANCELLED").contains(phase))state="WAITING";
    else if(!verified.satisfied()&&Set.of("COMPLETED","BLOCKED").contains(phase)&&checks.stream().anyMatch(c->c.required()&&c.satisfied()))state="PARTIAL";
    String reason=Set.of("FAILED","WAITING_APPROVAL","BLOCKED","CANCELLED","PAUSED").contains(goal.status())&&!goal.reason().isBlank()?goal.reason():verified.reason();
    if(goal.status().equals("WAITING_APPROVAL"))reason="permission_required";
    return new GoalCompletionProgress(goal.id(),goal.currentRunId(),state,verified,List.copyOf(checks),delivery,GoalRepairDiagnosis.of(reason));
  }
  private static Check check(String id,String statement,boolean required,Path root,GoalRepository.FileCriterion criterion,FileGoalVerifier verifier){
    var observed=verifier.verify(root,criterion);var fingerprint=verifier.fingerprint(root,criterion.relativeFile());
    return new Check(id,statement,required,observed.satisfied(),observed.reason(),fingerprint.sha256());
  }
}
