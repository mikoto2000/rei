package dev.mikoto2000.rei.core.policy;

import java.util.UUID;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

/** Reports a refusal through existing durable Tool events before invoking the callback. */
@Component
public class ToolPermissionGuard {
  private ToolApprovalRepository approvals;
  @org.springframework.beans.factory.annotation.Autowired
  public void setApprovals(ToolApprovalRepository approvals) {this.approvals=approvals;}
  private final ToolPermissionPolicy policy;
  private final AgentEventFactory events;
  private final AgentEventPublisher publisher;
  public ToolPermissionGuard(ToolPermissionPolicy policy,AgentEventFactory events,AgentEventPublisher publisher) {
    this.policy=policy;this.events=events;this.publisher=publisher;
  }
  public void check(String tool,AgentRunContext owner) {
    check(tool,"",owner);
  }
  public void check(String tool,String input,AgentRunContext owner) {
    var decision=policy.evaluate(tool);
    if(decision==PermissionDecision.AUTO_APPROVE)return;
    String request=null;
    if(decision==PermissionDecision.REQUIRE_APPROVAL && approvals!=null && owner!=null && owner.projectId()!=null
        && !owner.conversationId().startsWith("subagent:")) {
      if(approvals.consume(tool,input,owner))return;
      request=approvals.request(tool,input,owner).id();
    }
    var error=new ToolPermissionException(tool,decision);
    publisher.publishBoundary(events.toolFailed("permission-"+UUID.randomUUID(),tool,
        new ErrorInformation(decision==PermissionDecision.DENY?"PermissionDenied":"PermissionRequired",
            request==null?error.getMessage():"Approval required: "+request+". Review with /approval show "+request+"; approve explicitly, then resume or retry the task.",decision.name())).withOwnership(owner));
    throw error;
  }
}
