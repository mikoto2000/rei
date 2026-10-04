package dev.mikoto2000.rei.core.policy;

import java.util.UUID;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

/** Reports a refusal through existing durable Tool events before invoking the callback. */
@Component
public class ToolPermissionGuard {
  private final ToolPermissionPolicy policy;
  private final AgentEventFactory events;
  private final AgentEventPublisher publisher;
  public ToolPermissionGuard(ToolPermissionPolicy policy,AgentEventFactory events,AgentEventPublisher publisher) {
    this.policy=policy;this.events=events;this.publisher=publisher;
  }
  public void check(String tool,AgentRunContext owner) {
    var decision=policy.evaluate(tool);
    if(decision==PermissionDecision.AUTO_APPROVE)return;
    var error=new ToolPermissionException(tool,decision);
    publisher.publishBoundary(events.toolFailed("permission-"+UUID.randomUUID(),tool,
        new ErrorInformation(decision==PermissionDecision.DENY?"PermissionDenied":"PermissionRequired",
            error.getMessage(),decision.name())).withOwnership(owner));
    throw error;
  }
}
