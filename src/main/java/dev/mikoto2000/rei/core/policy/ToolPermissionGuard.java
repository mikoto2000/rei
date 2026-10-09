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
  /** A one-use approval is never a reusable grant for background read retries. */
  public boolean automaticallyApprovedRead(String tool){return policy.evaluate(tool)==PermissionDecision.AUTO_APPROVE&&policy.capabilities(tool).stream().allMatch(c->c==ActionCapability.READ||c==ActionCapability.NETWORK_READ);}
  public void check(String tool,String input,AgentRunContext owner) {
    check(tool,input,owner,owner!=null&&!owner.conversationId().startsWith("subagent:")?owner:null);
  }
  /** Only the runner passes its captured parent; no model-supplied identity or wildcard grant. */
  public void checkDelegated(String tool,String input,AgentRunContext child,AgentRunContext parent) {
    boolean matching=child!=null&&parent!=null&&child.conversationId().startsWith("subagent:")
        &&!parent.conversationId().startsWith("subagent:")&&parent.projectId()!=null
        &&parent.projectId().equals(child.projectId())&&parent.projectRoot().equals(child.projectRoot())
        &&parent.requestSource()==child.requestSource()&&parent.voiceInput()==child.voiceInput()
        &&policy.capabilities(tool).stream().allMatch(capability->capability==ActionCapability.READ||capability==ActionCapability.NETWORK_READ);
    check(tool,input,child,matching?parent:null);
  }
  private void check(String tool,String input,AgentRunContext owner,AgentRunContext approvalOwner) {
    boolean restricted = owner != null && (owner.mode() == AgentRunContext.Mode.CONVERSATION
        || owner.mode() == AgentRunContext.Mode.READ_ONLY && !ToolPermissionPolicy.intrinsicallyReadOnly(tool));
    var decision=restricted ? PermissionDecision.DENY : policy.evaluate(tool);
    // Recognized text is not a calibrated confidence/intent guarantee. Retain the existing
    // deny decision, and require the existing exact-input one-use approval for side effects.
    if(decision==PermissionDecision.AUTO_APPROVE&&owner!=null&&owner.voiceInput()
        &&!ToolPermissionPolicy.intrinsicallyReadOnly(tool))decision=PermissionDecision.REQUIRE_APPROVAL;
    if(decision==PermissionDecision.AUTO_APPROVE)return;
    String request=null;
    if(decision==PermissionDecision.REQUIRE_APPROVAL && approvals!=null && approvalOwner!=null && approvalOwner.projectId()!=null) {
      if(approvals.consume(tool,input,approvalOwner))return;
      request=approvals.request(tool,input,approvalOwner).id();
    }
    var error=new ToolPermissionException(tool,decision);
    publisher.publishBoundary(events.toolFailed("permission-"+UUID.randomUUID(),tool,
        new ErrorInformation(decision==PermissionDecision.DENY?"PermissionDenied":"PermissionRequired",
            request==null?error.getMessage():"Approval required: "+request+". Review with /approval show "+request+"; approve explicitly, then resume or retry the task.",decision.name())).withOwnership(owner));
    throw error;
  }
}
