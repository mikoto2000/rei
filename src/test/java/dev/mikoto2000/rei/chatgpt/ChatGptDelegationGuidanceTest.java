package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import dev.mikoto2000.rei.subagent.*;

class ChatGptDelegationGuidanceTest {
  private final StaticListableBeanFactory beans=new StaticListableBeanFactory();
  private final PlanOAuth oauth=mock(PlanOAuth.class);
  private ChatGptDelegationGuidance guidance(boolean automatic) {
    beans.addBean("plan",new PlanProperties(true,Path.of("secret-credential-directory"),Set.of("reviewer","missing")));
    beans.addBean("automatic",new PlanDelegationProperties(automatic,Set.of("default"),Set.of("gpt-6.1-sol")));
    beans.addBean("oauth",oauth);
    var registry=mock(SubAgentRegistry.class);
    when(registry.findById("reviewer")).thenReturn(Optional.of(new SubAgentDefinition("reviewer","Reviewer","review","instructions",List.of("readMultiFile"),"model",4,Duration.ofSeconds(30),Path.of("role.yaml"))));
    when(registry.findById("missing")).thenReturn(Optional.empty());beans.addBean("registry",registry);
    return new ChatGptDelegationGuidance(beans.getBeanProvider(PlanProperties.class),beans.getBeanProvider(PlanDelegationProperties.class),beans.getBeanProvider(PlanOAuth.class),beans.getBeanProvider(SubAgentRegistry.class));
  }
  @Test void configuredAuthorizedRouteNamesExactToolAndDestinationsWithoutCredentials() {
    when(oauth.authorizedAccounts()).thenReturn(Set.of("default","unallowed"));
    String prompt=guidance(true).prompt();
    assertTrue(prompt.contains("does not require durable SubAgents"));
    assertTrue(prompt.contains("Do not call listDurableSubAgents/getDurableSubAgent to discover ChatGPT configuration"));
    for(String masking:List.of("original Project-relative sourceRefs", "in-memory source snapshots",
        "does not change the original files", "redacted=true", "unverified", "never guess",
        "references.sha256 and quote", "262144", "recursively bisects", "whole plan once",
        "Receipt.groups", "remainingScopes", "limits never reset"))assertTrue(prompt.contains(masking),masking);
    assertTrue(prompt.contains("Copy accountRef and modelId exactly from these lists"));
    assertTrue(prompt.contains("A model appearing in /chatgpt models is not automatically allowed for delegation"));
    for(String required:List.of("delegateTask", "delegateTasks", "backendId=chatgpt-plan", "reviewer", "accountRef", "default", "modelId", "gpt-6.1-sol", "sourceRefs", "expectedOutput", "approval still apply", "not a live catalog check"))assertTrue(prompt.contains(required),required);
    for(String excluded:List.of("secret-credential-directory","unallowed","missing"))assertFalse(prompt.contains(excluded));
    verify(oauth).authorizedAccounts();verifyNoMoreInteractions(oauth);
  }
  @Test void loginDoesNotImplyAutomaticDelegation() {
    String prompt=guidance(false).prompt();assertTrue(prompt.contains("auto-delegation.enabled is disabled"));
    verifyNoInteractions(oauth);
  }
  @Test void missingAuthenticationIsReportedAsASeparateCondition() {
    when(oauth.authorizedAccounts()).thenReturn(Set.of());
    assertTrue(guidance(true).prompt().contains("no allowed account has saved ChatGPT plan authorization"));
  }
  @Test void failureToReadAuthenticationNeverLeaksTheErrorText() {
    when(oauth.authorizedAccounts()).thenThrow(new IllegalStateException("private token"));
    String prompt=guidance(true).prompt();assertTrue(prompt.contains("status could not be read"));assertFalse(prompt.contains("private token"));
  }
  @Test void disabledBackendIsDistinguishedFromCodexAndDoesNotLoadCredentials() {
    var guidance=new ChatGptDelegationGuidance(beans.getBeanProvider(PlanProperties.class),beans.getBeanProvider(PlanDelegationProperties.class),beans.getBeanProvider(PlanOAuth.class),beans.getBeanProvider(SubAgentRegistry.class));
    assertTrue(guidance.prompt().contains("chatgpt-plan.enabled is disabled"));verifyNoInteractions(oauth);
  }
  @Test void wrongProviderHintRequiresAHumanChatGptRequest() {
    when(oauth.authorizedAccounts()).thenReturn(Set.of("default"));var guidance=guidance(true);
    assertTrue(guidance.rejectionHint("ChatGPT に全資料をレビューして貰ってください").contains("backendId=chatgpt-plan"));
    assertEquals("",guidance.rejectionHint("Codex にレビューして"));
  }
}
