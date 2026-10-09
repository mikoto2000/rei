package dev.mikoto2000.rei.goal;
/** Conservative classification of host-observed reasons. Unknown results never authorize replay. */
public record GoalRepairDiagnosis(Kind kind,boolean repairable,boolean humanRequired,String reason) {
 public enum Kind { NONE,TEST_FAILURE,IMPLEMENTATION_INCONSISTENCY,REQUIREMENT_UNMET,TRANSIENT_EXTERNAL,APPROVAL_WAIT,UNKNOWN_RESULT,UNREPAIRABLE,BUDGET_INSUFFICIENT,CANCELLED }
 public static GoalRepairDiagnosis of(String reason){
  String value=reason==null?"":reason;
  return switch(value){
   case "","file_digest_verified","criteria_verified","completion_gate_verified","json_value_verified","predicate_verified" -> new GoalRepairDiagnosis(Kind.NONE,false,false,value);
   case "completion_tests_failed","completion_test_evidence_changed","completion_required_tests_missing" -> new GoalRepairDiagnosis(Kind.TEST_FAILURE,true,false,value);
   case "digest_mismatch","completion_review_stale","completion_review_not_verified" -> new GoalRepairDiagnosis(Kind.IMPLEMENTATION_INCONSISTENCY,true,false,value);
   case "json_value_mismatch","predicate_mismatch","file_missing_or_not_regular","completion_evidence_missing","completion_required_artifact_missing","completion_requirement_unmet" -> new GoalRepairDiagnosis(Kind.REQUIREMENT_UNMET,true,false,value);
   case "completion_delivery_pending" -> new GoalRepairDiagnosis(Kind.REQUIREMENT_UNMET,false,true,value);
   case "external_transient" -> new GoalRepairDiagnosis(Kind.TRANSIENT_EXTERNAL,false,false,value);
   case "permission_required" -> new GoalRepairDiagnosis(Kind.APPROVAL_WAIT,false,true,value);
   case "policy_denied","owner_unavailable","project_path_changed","symbolic_link_rejected","outside_project","file_too_large","json_file_too_large","predicate_disabled","completion_definition_missing","completion_gate_unavailable","completion_requirement_mismatch","completion_artifact_unavailable" -> new GoalRepairDiagnosis(Kind.UNREPAIRABLE,false,true,value);
   case "budget_exhausted","token_budget_exhausted","llm_call_budget_exceeded","replan_budget_exceeded","repair_reserve_reached" -> new GoalRepairDiagnosis(Kind.BUDGET_INSUFFICIENT,false,true,value);
   case "human_cancelled","run_cancelled","verification_cancelled" -> new GoalRepairDiagnosis(Kind.CANCELLED,false,false,value);
   default -> new GoalRepairDiagnosis(Kind.UNKNOWN_RESULT,false,true,value);
  };
 }
}
