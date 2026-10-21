package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Self変更差分レビューツールの結果と状態の契約を検証するテスト。 */
class SelfPatchReviewToolTest {
  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void savedRepairToolRetainsArbitraryCommandPolicyAndDoesNotGrantChildAgentAccess() {
    var callback =
        Arrays.stream(
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                    .toolObjects(new Tools())
                    .build()
                    .getToolCallbacks())
            .filter(c -> c.getToolDefinition().name().equals("selfRepairPatch"))
            .findFirst()
            .orElseThrow();
    assertTrue(callback.getToolDefinition().inputSchema().contains("repairs"));
    var policy =
        new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
            new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true, null, null, null));
    assertEquals(policy.capabilities("selfReviewPatch"), policy.capabilities("selfRepairPatch"));
    assertEquals(
        dev.mikoto2000.rei.core.policy.PermissionDecision.DENY, policy.evaluate("selfRepairPatch"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new dev.mikoto2000.rei.subagent.SubAgentToolPolicy(Set.of("selfRepairPatch"))
                .validate(List.of("selfRepairPatch")));
  }

  /** 共通の実行基盤の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void toolIsExplicitAndDoesNotGainReadOnlyOrChildAgentPermissions() {
    var callback =
        Arrays.stream(
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                    .toolObjects(new Tools())
                    .build()
                    .getToolCallbacks())
            .filter(c -> c.getToolDefinition().name().equals("selfReviewPatch"))
            .findFirst()
            .orElseThrow();
    assertTrue(callback.getToolDefinition().inputSchema().contains("testCommand"));
    var policy =
        new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
            new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true, null, null, null));
    assertEquals(policy.capabilities("runCommand"), policy.capabilities("selfReviewPatch"));
    assertEquals(
        dev.mikoto2000.rei.core.policy.PermissionDecision.DENY, policy.evaluate("selfReviewPatch"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new dev.mikoto2000.rei.subagent.SubAgentToolPolicy(Set.of("selfReviewPatch"))
                .validate(List.of("selfReviewPatch")));
  }
}
