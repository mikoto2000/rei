package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConcurrentRunPermissionTest {
  @Test void readOnlyAuthorityCannotBeEscalatedByDisabledPolicyOrConfiguredShellCapability() {
    var policy = new ToolPermissionPolicy(new ToolPermissionProperties(false, null, null,
        Map.of("runCommand", Set.of(ActionCapability.READ), "applyTextDiff", Set.of(ActionCapability.READ))));
    var guard = new ToolPermissionGuard(policy, new AgentEventFactory(Clock.systemUTC()), event -> {});
    var owner = new AgentRunContext("r", "s", Path.of("."), "p", AgentRunContext.RequestSource.WEB,
        AgentRunContext.Mode.READ_ONLY);
    assertThatCode(() -> guard.check("readMultiFile", "{}", owner)).doesNotThrowAnyException();
    for (String tool : List.of("runCommand", "applyTextDiff", "unknownMcpTool", "delegateTasks"))
      assertThatThrownBy(() -> guard.check(tool, "{}", owner)).isInstanceOf(ToolPermissionException.class);
  }
  @Test void conversationCannotUseEvenReadToolsButExclusiveRunsRetainConfiguredPolicy() {
    var guard = new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(false, null, null, null)),
        new AgentEventFactory(Clock.systemUTC()), event -> {});
    var conversation = new AgentRunContext("r", "s", Path.of("."), "p", AgentRunContext.RequestSource.WEB,
        AgentRunContext.Mode.CONVERSATION);
    assertThatThrownBy(() -> guard.check("readFile", "{}", conversation)).isInstanceOf(ToolPermissionException.class);
    assertThatCode(() -> guard.check("applyTextDiff", "{}", new AgentRunContext("r", "s", Path.of("."), "p")))
        .doesNotThrowAnyException();
  }
}
