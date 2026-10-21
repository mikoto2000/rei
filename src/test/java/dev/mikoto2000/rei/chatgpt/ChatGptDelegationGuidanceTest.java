package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.subagent.SubAgentDefinition;
import dev.mikoto2000.rei.subagent.SubAgentRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** チャットGPT委譲案内の結果と状態の契約を検証するテスト。 */
class ChatGptDelegationGuidanceTest {
  /** 検証に使う機能の構成要素一覧。 */
  private final StaticListableBeanFactory beans = new StaticListableBeanFactory();

  /** 検証に使うOAuth認証。 */
  private final PlanOAuth oauth = mock(PlanOAuth.class);

  /** {@code guidance} の条件を再現するための補助情報を返す。 */
  private ChatGptDelegationGuidance guidance(boolean automatic) {
    beans.addBean(
        "plan",
        new PlanProperties(
            true, Path.of("secret-credential-directory"), Set.of("reviewer", "missing")));
    beans.addBean(
        "automatic",
        new PlanDelegationProperties(automatic, Set.of("default"), Set.of("gpt-6.1-sol")));
    beans.addBean("oauth", oauth);
    var registry = mock(SubAgentRegistry.class);
    when(registry.findById("reviewer"))
        .thenReturn(
            Optional.of(
                new SubAgentDefinition(
                    "reviewer",
                    "Reviewer",
                    "review",
                    "instructions",
                    List.of("readMultiFile"),
                    "model",
                    4,
                    Duration.ofSeconds(30),
                    Path.of("role.yaml"))));
    when(registry.findById("missing")).thenReturn(Optional.empty());
    beans.addBean("registry", registry);
    return new ChatGptDelegationGuidance(
        beans.getBeanProvider(PlanProperties.class),
        beans.getBeanProvider(PlanDelegationProperties.class),
        beans.getBeanProvider(PlanOAuth.class),
        beans.getBeanProvider(SubAgentRegistry.class));
  }

  /** ChatGPTとの連携で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void configuredAuthorizedRouteNamesExactToolAndDestinationsWithoutCredentials() {
    when(oauth.authorizedAccounts()).thenReturn(Set.of("default", "unallowed"));
    String prompt = guidance(true).prompt();
    assertTrue(prompt.contains("does not require durable SubAgents"));
    assertTrue(
        prompt.contains(
            "Do not call listDurableSubAgents/getDurableSubAgent to discover ChatGPT configuration"));
    for (String masking :
        List.of(
            "original Project-relative sourceRefs",
            "in-memory source snapshots",
            "does not change the original files",
            "redacted=true",
            "unverified",
            "never guess",
            "references.sha256 and quote",
            "262144",
            "recursively bisects",
            "whole plan once",
            "Receipt.groups",
            "remainingScopes",
            "limits never reset")) assertTrue(prompt.contains(masking), masking);
    assertTrue(prompt.contains("Copy accountRef and modelId exactly from these lists"));
    assertTrue(
        prompt.contains(
            "A model appearing in /chatgpt models is not automatically allowed for delegation"));
    for (String required :
        List.of(
            "delegateTask",
            "delegateTasks",
            "backendId=chatgpt-plan",
            "reviewer",
            "accountRef",
            "default",
            "modelId",
            "gpt-6.1-sol",
            "sourceRefs",
            "expectedOutput",
            "approval still apply",
            "not a live catalog check")) assertTrue(prompt.contains(required), required);
    for (String excluded : List.of("secret-credential-directory", "unallowed", "missing"))
      assertFalse(prompt.contains(excluded));
    verify(oauth).authorizedAccounts();
    verifyNoMoreInteractions(oauth);
  }

  /** ChatGPTとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void loginDoesNotImplyAutomaticDelegation() {
    String prompt = guidance(false).prompt();
    assertTrue(prompt.contains("auto-delegation.enabled is disabled"));
    verifyNoInteractions(oauth);
  }

  /** ChatGPTとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void missingAuthenticationIsReportedAsASeparateCondition() {
    when(oauth.authorizedAccounts()).thenReturn(Set.of());
    assertTrue(
        guidance(true)
            .prompt()
            .contains("no allowed account has saved ChatGPT plan authorization"));
  }

  /** 失敗情報終点について、認証決して行わない漏えいエラーテキストを読み取ることを確認する。 */
  @Test
  void failureToReadAuthenticationNeverLeaksTheErrorText() {
    when(oauth.authorizedAccounts()).thenThrow(new IllegalStateException("private token"));
    String prompt = guidance(true).prompt();
    assertTrue(prompt.contains("status could not be read"));
    assertFalse(prompt.contains("private token"));
  }

  /** ChatGPTとの連携で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void disabledBackendIsDistinguishedFromCodexAndDoesNotLoadCredentials() {
    var guidance =
        new ChatGptDelegationGuidance(
            beans.getBeanProvider(PlanProperties.class),
            beans.getBeanProvider(PlanDelegationProperties.class),
            beans.getBeanProvider(PlanOAuth.class),
            beans.getBeanProvider(SubAgentRegistry.class));
    assertTrue(guidance.prompt().contains("chatgpt-plan.enabled is disabled"));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(oauth);
  }

  /** 利用者チャットGPT要求を必要とすることを確認する。 */
  @Test
  void wrongProviderHintRequiresAHumanChatGptRequest() {
    when(oauth.authorizedAccounts()).thenReturn(Set.of("default"));
    var guidance = guidance(true);
    assertTrue(
        guidance.rejectionHint("ChatGPT に全資料をレビューして貰ってください").contains("backendId=chatgpt-plan"));
    assertEquals("", guidance.rejectionHint("Codex にレビューして"));
  }
}
