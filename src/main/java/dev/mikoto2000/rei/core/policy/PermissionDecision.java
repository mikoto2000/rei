package dev.mikoto2000.rei.core.policy;

/** ツール利用の権限制御における権限判断結果の選択肢。 */
public enum PermissionDecision {
  /** 自動承認。 */
  AUTO_APPROVE,
  /** 必須条件承認。 */
  REQUIRE_APPROVAL,
  /** 拒否。 */
  DENY
}
