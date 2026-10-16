package dev.mikoto2000.rei.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.PermissionDecision;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DoctorActivePolicyTest {
  @Test
  void diagnosticsHaveExplicitIntrinsicAuthority() {
    var policy = new ToolPermissionPolicy(new ToolPermissionProperties(true, null, null, null));
    for (String name : List.of("doctorConnectivity", "doctorInference")) {
      assertEquals(
          Set.of(ActionCapability.NETWORK_READ), ToolPermissionPolicy.intrinsicCapabilities(name));
      assertTrue(ToolPermissionPolicy.intrinsicallyReadOnly(name));
      assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate(name));
    }
    for (String name : List.of("doctorCodexVersion", "doctorClaudeVersion")) {
      assertEquals(
          Set.of(ActionCapability.EXECUTE), ToolPermissionPolicy.intrinsicCapabilities(name));
      assertFalse(ToolPermissionPolicy.intrinsicallyReadOnly(name));
    }
    assertEquals(
        Set.of(ActionCapability.EXECUTE, ActionCapability.EXTERNAL_SIDE_EFFECT),
        ToolPermissionPolicy.intrinsicCapabilities("doctorMicrophone"));
  }

  @Test
  void requestGrammarIsBoundedExplicitAndDoesNotAcceptCommands() {
    assertTrue(DoctorRequest.parse(new String[] {"--details"}).checks().isEmpty());
    assertEquals(
        List.of(DoctorRequest.Check.CONNECTIVITY, DoctorRequest.Check.INFERENCE),
        DoctorRequest.parseText("/doctor --check connectivity --check inference").checks());
    assertThrows(
        IllegalArgumentException.class,
        () -> DoctorRequest.parse(new String[] {"--check", "arbitrary-command-secret"}));
    assertThrows(IllegalArgumentException.class, () -> DoctorRequest.parse(new String[33]));
  }
}
