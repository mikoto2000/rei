package dev.mikoto2000.rei.core.policy;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.subagent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DelegationToolPermissionTest {
  @Test void missingAccountIsRejectedBeforeRequestingOrConsumingApproval() {
    var repository=mock(ToolApprovalRepository.class);var guard=guard();guard.setApprovals(repository);
    String input="{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\",\"modelId\":\"gpt-6.1-sol\",\"sourceRefs\":[\"docs/design.md\"],\"expectedOutput\":\"{\\\"unverified\\\":[]}\"}";
    var error=assertThrows(InvalidToolArgumentsException.class,()->guard.check("delegateTask",input,owner(AgentRunContext.Mode.EXCLUSIVE,"session")));
    assertTrue(error.getMessage().contains("ACCOUNT_REQUIRED"));verifyNoInteractions(repository);
  }
  private ToolPermissionPolicy policy;
  private SubAgentRegistry registry;
  @BeforeEach @SuppressWarnings("unchecked") void setup() {
    registry=mock(SubAgentRegistry.class);
    when(registry.findById("reviewer")).thenReturn(Optional.of(definition("reviewer",List.of("readMultiFile"))));
    when(registry.findById("researcher")).thenReturn(Optional.of(definition("researcher",List.of("webSearch"))));
    when(registry.findById("writer")).thenReturn(Optional.of(definition("writer",List.of("writeMultiFile"))));
    when(registry.findById("judge")).thenReturn(Optional.of(definition("judge",List.of())));
    ObjectProvider<SubAgentRegistry> registries=mock(ObjectProvider.class);
    when(registries.getObject()).thenReturn(registry);
    ObjectProvider<SubAgentToolPolicy> policies=mock(ObjectProvider.class);
    when(policies.getObject()).thenReturn(new SubAgentToolPolicy(Set.of("readMultiFile","webSearch","writeMultiFile")));
    policy=new ToolPermissionPolicy(new ToolPermissionProperties(true,null,null,null));
    policy.setDelegationResolver(new DelegationToolPermissionResolver(registries,policies));
  }
  private SubAgentDefinition definition(String agent,List<String> tools) {
    return new SubAgentDefinition(agent,agent,"test","test",tools,"model",4,Duration.ofSeconds(30),Path.of("agent.yaml"));
  }
  private AgentRunContext owner(AgentRunContext.Mode mode,String session) {
    return new AgentRunContext("run",session,Path.of("."),"project",AgentRunContext.RequestSource.WEB,mode);
  }
  private ToolPermissionGuard guard() {
    return new ToolPermissionGuard(policy,new AgentEventFactory(Clock.systemUTC()),event->{});
  }
  @Test void blankBackendIsLocalForPermissionChecks() {
    String input="{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"  \",\"accountRef\":\"\",\"modelId\":\"\",\"context\":\"\",\"sourceRefs\":[],\"expectedOutput\":\"\"}";
    assertEquals(Set.of(ActionCapability.READ),policy.capabilities("delegateTask",input));
    assertDoesNotThrow(()->guard().check("delegateTask",input,owner(AgentRunContext.Mode.EXCLUSIVE,"session")));
  }
  @Test void expectedOutputTemplateIsRejectedBeforeApprovalCreationOrConsumption() {
    var original=definition("reviewer",List.of("readMultiFile"));
    var schema=SubAgentResultSchema.load(Path.of("."),"classpath:/subagents/schemas/reviewer-result.schema.json");
    when(registry.findById("reviewer")).thenReturn(Optional.of(new SubAgentDefinition(original.id(),original.name(),original.description(),original.systemPrompt(),original.requestedTools(),original.model(),original.maxSteps(),original.timeout(),original.source(),schema)));
    var repository=mock(ToolApprovalRepository.class);var guard=guard();guard.setApprovals(repository);
    String input="{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\",\"accountRef\":\"default\",\"modelId\":\"gpt-6.1-sol\",\"sourceRefs\":[\"docs/design.md\"],\"expectedOutput\":\"{\\\"summary\\\":\\\"string\\\"}\"}";
    var error=assertThrows(IllegalArgumentException.class,()->guard.check("delegateTask",input,owner(AgentRunContext.Mode.EXCLUSIVE,"session")));
    assertTrue(error.getMessage().contains("exact-match"));verifyNoInteractions(repository);
  }
  @Test void invalidArgumentsNeverCreateOrConsumeApprovalEvenWithoutADenyList() {
    policy=new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(),Set.of(),Map.of()));
    var repository=mock(ToolApprovalRepository.class);var guard=guard();guard.setApprovals(repository);
    for(String input:List.of(
        "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"unknown\"}",
        "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"\",\"sourceRefs\":[\"docs/a.md\"],\"expectedOutput\":\"JSON\"}",
        "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":true}",
        "{\"agent\":\"reviewer\"}"))
      assertThrows(IllegalArgumentException.class,()->guard.check("delegateTask",input,owner(AgentRunContext.Mode.EXCLUSIVE,"session")));
    assertThrows(IllegalArgumentException.class,()->guard.check("delegateTasks",
        "{\"requests\":[{\"id\":\"same\",\"agent\":\"reviewer\",\"task\":\"review\"},{\"id\":\"same\",\"agent\":\"reviewer\",\"task\":\"review\"}]}",owner(AgentRunContext.Mode.EXCLUSIVE,"session")));
    verifyNoInteractions(repository);
  }
  @Test void localSingleAndParallelReviewsPassTheRealGuardWithDefaultPolicy() {
    var owner=owner(AgentRunContext.Mode.READ_ONLY,"session");
    assertDoesNotThrow(()->guard().check("delegateTask","{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"}",owner));
    assertDoesNotThrow(()->guard().check("delegateTasks","{\"requests\":[{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"},{\"agent\":\"judge\",\"task\":\"judge\",\"id\":\"judge\",\"backendId\":\"local\"}]}",owner));
  }
  @Test void requiredChildCapabilitiesAreCombinedBeforeAnyChildStarts() {
    String input="{\"requests\":[{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"},{\"agent\":\"researcher\",\"task\":\"research\",\"id\":\"research\"}]}";
    assertEquals(Set.of(ActionCapability.READ,ActionCapability.NETWORK_READ),policy.capabilities("delegateTasks",input));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL,policy.evaluate("delegateTasks",input));
    assertThrows(ToolPermissionException.class,()->guard().check("delegateTasks",input,owner(AgentRunContext.Mode.EXCLUSIVE,"session")));
    assertEquals(PermissionDecision.AUTO_APPROVE,policy.evaluate("delegateTask","{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"}"));
  }
  @Test void externalDestinationsRequireSendingAndAppServerRequiresExecution() {
    for(String tool:List.of("delegateTask","delegateTasks")) {
      for(String backend:List.of("chatgpt-plan","codex-app-server")) {
        String request="{\"agent\":\"reviewer\",\"backendId\":\""+backend+"\"}";
        String input=tool.equals("delegateTasks")?"{\"requests\":[{\"agent\":\"judge\"},"+request+"]}":request;
        var required=policy.capabilities(tool,input);
        assertTrue(required.containsAll(Set.of(ActionCapability.NETWORK_WRITE,ActionCapability.EXTERNAL_SIDE_EFFECT)));
        assertEquals(backend.equals("codex-app-server"),required.contains(ActionCapability.EXECUTE));
        assertEquals(PermissionDecision.REQUIRE_APPROVAL,policy.evaluate(tool,input));
        assertThrows(ToolPermissionException.class,()->guard().check(tool,input,owner(AgentRunContext.Mode.READ_ONLY,"session")));
      }
    }
  }
  @Test void unknownMalformedAndDisallowedChildrenFailClosed() {
    for(String input:List.of("{}","not json","{\"agent\":\"missing\"}","{\"agent\":\"writer\"}",
        "{\"agent\":\"reviewer\",\"backendId\":\"unknown\"}","{\"agent\":\"reviewer\",\"backendId\":true}"))
      assertEquals(PermissionDecision.DENY,policy.evaluate("delegateTask",input));
    for(String input:List.of("{\"requests\":[]}","{\"requests\":[{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"},{\"agent\":\"missing\"}]}"))
      assertEquals(PermissionDecision.DENY,policy.evaluate("delegateTasks",input));
  }
  @Test void disabledPolicyAndReadRelabelingCannotAuthorizeExternalOrRecursiveDelegation() {
    var resolver=policy.capabilities("delegateTask","{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"}");
    assertEquals(Set.of(ActionCapability.READ),resolver);
    assertThrows(ToolPermissionException.class,()->guard().check("delegateTask","{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"}",owner(AgentRunContext.Mode.EXCLUSIVE,"subagent:child")));
    var providers=new org.springframework.beans.factory.support.StaticListableBeanFactory();
    providers.addBean("registry",registry);
    providers.addBean("children",new SubAgentToolPolicy(Set.of("readMultiFile","webSearch")));
    policy=new ToolPermissionPolicy(new ToolPermissionProperties(false,null,null,Map.of("delegateTask",Set.of(ActionCapability.READ))));
    policy.setDelegationResolver(new DelegationToolPermissionResolver(providers.getBeanProvider(SubAgentRegistry.class),providers.getBeanProvider(SubAgentToolPolicy.class)));
    assertThrows(ToolPermissionException.class,()->guard().check("delegateTask","{\"agent\":\"reviewer\",\"backendId\":\"chatgpt-plan\"}",owner(AgentRunContext.Mode.READ_ONLY,"session")));
  }
  @Test void graphAndJudgeUseChildDefinitionsInsteadOfUnknownToolFallback() {
    assertEquals(PermissionDecision.AUTO_APPROVE,policy.evaluate("judgeSubAgentAnswers","{\"agent\":\"judge\"}"));
    assertEquals(Set.of(ActionCapability.READ,ActionCapability.NETWORK_READ,ActionCapability.LOCAL_WRITE),
        policy.capabilities("delegateTaskGraph","{\"spec\":{\"nodes\":[{\"agent\":\"researcher\",\"task\":\"research\",\"id\":\"research\"}]}}"));
  }
}
