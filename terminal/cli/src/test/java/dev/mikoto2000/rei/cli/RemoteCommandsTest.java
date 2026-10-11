package dev.mikoto2000.rei.cli;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
class RemoteCommandsTest {
  @org.junit.jupiter.api.Test void responseStyleIsSeparateFromRunAccess() {
    var request=RemoteCommands.route(java.util.List.of("/mode","conversation","--voice-only"),"project","session");
    org.junit.jupiter.api.Assertions.assertEquals("PATCH",request.method());
    org.junit.jupiter.api.Assertions.assertEquals("/api/v1/sessions/session/response-style",request.path());
    org.junit.jupiter.api.Assertions.assertEquals(java.util.Map.of("projectId","project","style","CONVERSATION","voiceOnly",true),request.body());
  }
  @Test void projectControlsCarryExplicitOwnershipAndNamedOperationsOnly() {
    var request=RemoteCommands.route(List.of("/approval","approve","request"),"project","session");
    assertEquals("/api/v1/projects/project/approvals/request/decision",request.path());assertEquals(Map.of("approved",true),request.body());
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/approval","execute","request"),"project","session"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/sh","rm","something"),"project","session"));
  }
  @Test void historyAndTasksUseTheirActualPublicApiRoutes() {
    assertEquals("/api/v1/sessions/session/turns",RemoteCommands.route(List.of("/history","show"),"project","session").path());
    assertEquals("/api/v1/tasks?projectId=project",RemoteCommands.route(List.of("/tasks","list"),"project","session").path());
    assertEquals("/api/v1/projects/project/attention/item/ack",RemoteCommands.route(List.of("/attention","ack","item"),"project","session").path());
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/checkpoint","list"),null,null));
  }
  @Test void dependencyAnswersPreserveQuotedTextAndExpectedRevision() {
    var request=RemoteCommands.route(List.of("/dependency","answer","dependency","3","quoted answer"),"project",null);
    assertEquals(Map.of("expectedVersion",3L,"answer","quoted answer"),request.body());
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/dependency","answer","dependency","-1","answer"),"project",null));
  }
}
