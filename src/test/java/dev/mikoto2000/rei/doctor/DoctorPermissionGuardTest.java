package dev.mikoto2000.rei.doctor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class DoctorPermissionGuardTest {
 @TempDir Path root;
 @Test void actualGuardMaintainsConversationReadonlyAndVoiceBoundaries() {
  var policy=new ToolPermissionPolicy(new ToolPermissionProperties(true,EnumSet.allOf(ActionCapability.class),Set.of(),Map.of()));
  var guard=new ToolPermissionGuard(policy,new AgentEventFactory(Clock.systemUTC()),mock(AgentEventPublisher.class));
  var conversation=new AgentRunContext("run","session",root,"project",AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.CONVERSATION);
  assertThrows(ToolPermissionException.class,()->guard.check("doctorInference","fingerprint",conversation));
  var readonly=new AgentRunContext("run","session",root,"project",AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.READ_ONLY);
  assertDoesNotThrow(()->guard.check("doctorConnectivity","fingerprint",readonly));
  assertThrows(ToolPermissionException.class,()->guard.check("doctorCodexVersion","fingerprint",readonly));
  assertThrows(ToolPermissionException.class,()->guard.check("doctorMicrophone","fingerprint",readonly));
  var voice=new AgentRunContext("run","session",root,"project",AgentRunContext.RequestSource.SHELL,AgentRunContext.Mode.EXCLUSIVE,true);
  var failure=assertThrows(ToolPermissionException.class,()->guard.check("doctorMicrophone","fingerprint",voice));
  assertEquals(PermissionDecision.REQUIRE_APPROVAL,failure.decision());
 }
}
