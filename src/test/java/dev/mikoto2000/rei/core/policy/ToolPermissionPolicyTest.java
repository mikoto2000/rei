package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.event.*;

class ToolPermissionPolicyTest {
  @Test void administratorConfigurationBindsCapabilitiesAndDefaults() {
    var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of(
        "rei.tool-permission.enabled","true",
        "rei.tool-permission.auto-approve[0]","READ",
        "rei.tool-permission.auto-approve[1]","NETWORK_READ",
        "rei.tool-permission.capabilities.my-read-tool[0]","NETWORK_READ"));
    var props=new org.springframework.boot.context.properties.bind.Binder(source)
        .bind("rei.tool-permission",ToolPermissionProperties.class).get();
    var p=new ToolPermissionPolicy(props);
    assertEquals(PermissionDecision.AUTO_APPROVE,p.evaluate("my-read-tool"));
    assertEquals(PermissionDecision.DENY,p.evaluate("runCommand"));
  }
  ToolPermissionPolicy policy(Set<ActionCapability> automatic, Set<ActionCapability> denied) {
    return new ToolPermissionPolicy(new ToolPermissionProperties(true,automatic,denied,Map.of()));
  }
  @Test void readCanBeAutomaticButUnknownToolsRequireApproval() {
    var policy=policy(Set.of(ActionCapability.READ),Set.of());
    assertEquals(PermissionDecision.AUTO_APPROVE,policy.evaluate("readMultiFile"));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL,policy.evaluate("unknownMcpTool"));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL,policy.evaluate("applyTextDiff"));
  }
  @Test void denialWinsAndAllCapabilitiesMustBeGranted() {
    var p=new ToolPermissionPolicy(new ToolPermissionProperties(true,
        Set.of(ActionCapability.EXECUTE),Set.of(ActionCapability.DESTRUCTIVE),
        Map.of("custom",Set.of(ActionCapability.EXECUTE,ActionCapability.DESTRUCTIVE))));
    assertEquals(PermissionDecision.DENY,p.evaluate("custom"));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL,p.evaluate("webSearch"));
  }
  @Test void configuredCapabilitiesAreImmutableAndCanGrantNetworkRead() {
    var grants=new HashSet<>(Set.of(ActionCapability.NETWORK_READ));
    var p=policy(grants,Set.of()); grants.clear();
    assertEquals(PermissionDecision.AUTO_APPROVE,p.evaluate("webSearch"));
  }
  @Test void legacyModeRetainsExistingToolBehavior() {
    var p=new ToolPermissionPolicy(new ToolPermissionProperties(false,null,null,null));
    assertEquals(PermissionDecision.AUTO_APPROVE,p.evaluate("unknownMcpTool"));
  }
  @Test void defaultEnforcedProfileDeniesUnclassifiedAndDestructiveActions() {
    var p=new ToolPermissionPolicy(new ToolPermissionProperties(true,null,null,null));
    assertEquals(PermissionDecision.DENY,p.evaluate("runCommand"));
    assertEquals(PermissionDecision.DENY,p.evaluate("deleteFile"));
    assertEquals(PermissionDecision.DENY,p.evaluate("unknownMcpTool"));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL,p.evaluate("searchKnowledge"));
  }
  @Test void eventStorageFailureStillPreventsExecution() {
    var guard=new ToolPermissionGuard(policy(Set.of(),Set.of()),new AgentEventFactory(Clock.systemUTC()),
        event->{throw new IllegalStateException("storage failure");});
    assertThrows(IllegalStateException.class,()->guard.check("readMultiFile",null));
  }
  @Test void guardReportsApprovalBeforeAnySideEffect() {
    List<AgentEvent> events=new ArrayList<>();
    var guard=new ToolPermissionGuard(policy(Set.of(ActionCapability.READ),Set.of()),
        new AgentEventFactory(Clock.systemUTC()),events::add);
    assertThrows(ToolPermissionException.class,()->guard.check("applyTextDiff",null));
    assertEquals(1,events.size());
    var failure=(ToolFailedPayload)events.getFirst().payload();
    assertEquals("PermissionRequired",failure.error().errorType());
    assertDoesNotThrow(()->guard.check("readMultiFile",null));
    assertEquals(1,events.size());
  }
}
