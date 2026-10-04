package dev.mikoto2000.rei.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SelfPatchReviewToolTest {
  @Test void toolIsExplicitAndDoesNotGainReadOnlyOrChildAgentPermissions() {
    var callback=Arrays.stream(org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(new Tools()).build().getToolCallbacks())
        .filter(c->c.getToolDefinition().name().equals("selfReviewPatch")).findFirst().orElseThrow();
    assertTrue(callback.getToolDefinition().inputSchema().contains("testCommand"));
    var policy=new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null));
    assertEquals(policy.capabilities("runCommand"),policy.capabilities("selfReviewPatch"));
    assertEquals(dev.mikoto2000.rei.core.policy.PermissionDecision.DENY,policy.evaluate("selfReviewPatch"));
    assertThrows(IllegalArgumentException.class,()->new dev.mikoto2000.rei.subagent.SubAgentToolPolicy(Set.of("selfReviewPatch")).validate(List.of("selfReviewPatch")));
  }
}
