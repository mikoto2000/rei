package dev.mikoto2000.rei.goal;

/**
 * 目標の達成管理で受け渡す目標修復診断を表す。
 *
 * <p>Conservative classification of host-observed reasons. Unknown results never authorize replay.
 *
 * @param kind 種類
 * @param repairable 修復できるかどうか
 * @param humanRequired 利用者必須
 * @param reason 理由
 */
public record GoalRepairDiagnosis(
    Kind kind, boolean repairable, boolean humanRequired, String reason) {
  /** 目標の達成管理における種類の選択肢。 */
  public enum Kind {
    /** 該当なし。 */
    NONE,
    /** 検証失敗情報。 */
    TEST_FAILURE,
    /** 実装INCONSISTENCY。 */
    IMPLEMENTATION_INCONSISTENCY,
    /** 要件未達成の条件。 */
    REQUIREMENT_UNMET,
    /** 一時的な外部。 */
    TRANSIENT_EXTERNAL,
    /** 承認待機。 */
    APPROVAL_WAIT,
    /** 不明結果。 */
    UNKNOWN_RESULT,
    /** 修復の対象にできない状態。 */
    UNREPAIRABLE,
    /** 予算INSUFFICIENT。 */
    BUDGET_INSUFFICIENT,
    /** キャンセル済み。 */
    CANCELLED
  }

  /** 目標の達成管理で受け渡す情報を、指定された内容から作成する。 */
  public static GoalRepairDiagnosis of(String reason) {
    String value = reason == null ? "" : reason;
    return switch (value) {
      case "",
          "file_digest_verified",
          "criteria_verified",
          "completion_gate_verified",
          "json_value_verified",
          "predicate_verified" ->
          new GoalRepairDiagnosis(Kind.NONE, false, false, value);
      case "completion_tests_failed",
          "completion_test_evidence_changed",
          "completion_required_tests_missing" ->
          new GoalRepairDiagnosis(Kind.TEST_FAILURE, true, false, value);
      case "digest_mismatch", "completion_review_stale", "completion_review_not_verified" ->
          new GoalRepairDiagnosis(Kind.IMPLEMENTATION_INCONSISTENCY, true, false, value);
      case "json_value_mismatch",
          "predicate_mismatch",
          "file_missing_or_not_regular",
          "completion_evidence_missing",
          "completion_required_artifact_missing",
          "completion_requirement_unmet" ->
          new GoalRepairDiagnosis(Kind.REQUIREMENT_UNMET, true, false, value);
      case "completion_delivery_pending" ->
          new GoalRepairDiagnosis(Kind.REQUIREMENT_UNMET, false, true, value);
      case "external_transient" ->
          new GoalRepairDiagnosis(Kind.TRANSIENT_EXTERNAL, false, false, value);
      case "permission_required" -> new GoalRepairDiagnosis(Kind.APPROVAL_WAIT, false, true, value);
      case "policy_denied",
          "owner_unavailable",
          "project_path_changed",
          "symbolic_link_rejected",
          "outside_project",
          "file_too_large",
          "json_file_too_large",
          "predicate_disabled",
          "completion_definition_missing",
          "completion_gate_unavailable",
          "completion_requirement_mismatch",
          "completion_artifact_unavailable" ->
          new GoalRepairDiagnosis(Kind.UNREPAIRABLE, false, true, value);
      case "budget_exhausted",
          "token_budget_exhausted",
          "llm_call_budget_exceeded",
          "replan_budget_exceeded",
          "repair_reserve_reached" ->
          new GoalRepairDiagnosis(Kind.BUDGET_INSUFFICIENT, false, true, value);
      case "human_cancelled", "run_cancelled", "verification_cancelled" ->
          new GoalRepairDiagnosis(Kind.CANCELLED, false, false, value);
      default -> new GoalRepairDiagnosis(Kind.UNKNOWN_RESULT, false, true, value);
    };
  }
}
