package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.computeruse.FullAutoOptions;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.DefaultApplicationArguments;

class ExistingBlueskyPublicationPermissionsTest {
  private ToolPermissionPolicy policy(
      boolean enabled, Set<ActionCapability> auto, Set<ActionCapability> denied, boolean fullAuto) {
    return new ToolPermissionPolicy(
        new ToolPermissionProperties(enabled, auto, denied, Map.of()),
        new FullAutoOptions(new DefaultApplicationArguments("--fullauto=" + fullAuto)));
  }

  @ParameterizedTest
  @CsvSource({
    "NETWORK_WRITE,false",
    "NETWORK_WRITE,true",
    "EXTERNAL_SIDE_EFFECT,false",
    "EXTERNAL_SIDE_EFFECT,true"
  })
  void existingDeniedSendCapabilitiesRemainDeniedEvenWithFullAuto(
      ActionCapability denied, boolean fullAuto) {
    var policy = policy(true, Set.of(), Set.of(denied), fullAuto);
    assertThat(policy.evaluate("blueskyPost")).isEqualTo(PermissionDecision.DENY);
    assertThat(new ExistingBlueskyPublicationPermissions(policy).allowed()).isFalse();
  }

  @Test
  void anExplicitPublicationActionCanSatisfyTheExistingApprovalRequirement() {
    var policy = policy(true, Set.of(ActionCapability.READ), Set.of(), false);
    assertThat(policy.evaluate("blueskyPost")).isEqualTo(PermissionDecision.REQUIRE_APPROVAL);
    assertThat(new ExistingBlueskyPublicationPermissions(policy).allowed()).isTrue();
  }

  @Test
  void existingAutoApprovedSendCapabilitiesDoNotNeedAnotherIdentityMechanism() {
    var policy =
        policy(
            true,
            Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT),
            Set.of(),
            false);
    assertThat(policy.evaluate("blueskyPost")).isEqualTo(PermissionDecision.AUTO_APPROVE);
    assertThat(new ExistingBlueskyPublicationPermissions(policy).allowed()).isTrue();
  }

  @Test
  void respectsTheExistingPolicyDisabledSetting() {
    var policy = policy(false, Set.of(), Set.of(ActionCapability.NETWORK_WRITE), false);
    assertThat(policy.evaluate("blueskyPost")).isEqualTo(PermissionDecision.AUTO_APPROVE);
    assertThat(new ExistingBlueskyPublicationPermissions(policy).allowed()).isTrue();
  }
}
