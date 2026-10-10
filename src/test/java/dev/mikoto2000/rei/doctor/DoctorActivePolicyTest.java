package dev.mikoto2000.rei.doctor;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.policy.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DoctorActivePolicyTest {
  @Test void diagnosticsHaveExplicitIntrinsicAuthority() {
    var policy=new ToolPermissionPolicy(new ToolPermissionProperties(true,null,null,null));
    for(String name:List.of("doctorConnectivity","doctorInference")) {
      assertEquals(Set.of(ActionCapability.NETWORK_READ),ToolPermissionPolicy.intrinsicCapabilities(name));
      assertTrue(ToolPermissionPolicy.intrinsicallyReadOnly(name));
      assertEquals(PermissionDecision.REQUIRE_APPROVAL,policy.evaluate(name));
    }
    for(String name:List.of("doctorCodexVersion","doctorClaudeVersion")) {
      assertEquals(Set.of(ActionCapability.EXECUTE),ToolPermissionPolicy.intrinsicCapabilities(name));
      assertFalse(ToolPermissionPolicy.intrinsicallyReadOnly(name));
    }
    assertEquals(Set.of(ActionCapability.EXECUTE,ActionCapability.EXTERNAL_SIDE_EFFECT),ToolPermissionPolicy.intrinsicCapabilities("doctorMicrophone"));
  }
  @Test void requestGrammarIsBoundedExplicitAndDoesNotAcceptCommands() {
    assertTrue(DoctorRequest.parse(new String[]{"--details"}).checks().isEmpty());
    assertEquals(List.of(DoctorRequest.Check.CONNECTIVITY,DoctorRequest.Check.INFERENCE),DoctorRequest.parseText("/doctor --check connectivity --check inference").checks());
    assertThrows(IllegalArgumentException.class,()->DoctorRequest.parse(new String[]{"--check","arbitrary-command-secret"}));
    assertThrows(IllegalArgumentException.class,()->DoctorRequest.parse(new String[33]));
  }
}
