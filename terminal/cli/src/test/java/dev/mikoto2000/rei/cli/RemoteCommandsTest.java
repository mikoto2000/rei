package dev.mikoto2000.rei.cli;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
class RemoteCommandsTest {
  @Test void feedAndReminderAcceptShellOptionNamesAndDeletionGrammar() {
    assertEquals(Map.of("url","https://example.test/feed","displayName","example"),RemoteCommands.route(List.of("/feed","add","https://example.test/feed","--name","example"),null,null).body());
    assertEquals(Map.of("displayName","renamed","enabled",false),RemoteCommands.route(List.of("/feed","edit","1","--name","renamed","--disabled"),null,null).body());
    for(String command:List.of("/feed","/reminder","/memory"))assertEquals("DELETE",RemoteCommands.route(List.of(command,"delete","1"),null,null).method());
    var reminder=(Map<?,?>)RemoteCommands.route(List.of("/reminder","add","remember","the","fixture","--at","2026-10-11T09:00:00Z"),null,null).body();
    assertEquals("remember the fixture",reminder.get("message"));assertEquals("2026-10-11T09:00:00Z",reminder.get("at"));
    var before=(Map<?,?>)RemoteCommands.route(List.of("/reminder","add","--target","2026-10-11T09:00:00Z","--minutes-before","30","fixture"),null,null).body();
    assertEquals("fixture",before.get("message"));assertEquals(30L,before.get("minutesBefore"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/reminder","add","fixture","--at","2026-10-11T09:00:00Z","--target","2026-10-11T09:00:00Z","--minutes-before","30"),null,null));
  }
  @Test void interestListUsesShellHourOptionWithoutModelSubmission() {
    assertEquals("/api/v1/interests?hours=48",RemoteCommands.route(List.of("/interest","list","--hours","48"),null,null).path());
  }
  @Test void attentionDeliveryRetainsExplicitDuplicateRiskAcknowledgment() {
    assertEquals("GET",RemoteCommands.route(List.of("/attention","delivery","item"),"project",null).method());
    assertEquals(Map.of("retry",false,"acknowledgeDuplicateRisk",false),RemoteCommands.route(List.of("/attention","deliver","item"),"project",null).body());
    assertEquals(Map.of("retry",true,"acknowledgeDuplicateRisk",true),RemoteCommands.route(List.of("/attention","retry-delivery","item","--acknowledge-duplicate-risk"),"project",null).body());
    assertEquals(Map.of("retry",true,"acknowledgeDuplicateRisk",false),RemoteCommands.route(List.of("/attention","retry-delivery","item"),"project",null).body());
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/attention","deliver","item","--acknowledge-duplicate-risk"),"project",null));
  }
  @Test void goalCreationPreservesShellBudgetsAndCriterionOptions() {
    var request=RemoteCommands.route(List.of("/goal","create","objective","--file","out.txt","--sha256","a".repeat(64)),"project","session");
    assertEquals("/api/v1/projects/project/goals",request.path());
    assertEquals("session",((Map<?,?>)request.body()).get("sessionId"));
    assertEquals(3L,((Map<?,?>)request.body()).get("maxRuns"));assertEquals(20L,((Map<?,?>)request.body()).get("maxLlmCalls"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/goal","create","objective","--file","out.txt","--criteria-json","[]"),"project","session"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/goal","create","objective","--file","out.txt","--sha256","a".repeat(64)),"project",null));
  }
  @Test void goalCompletionSendsJsonObjectAndRequiresExactlyOneDefinitionOrProof() {
    var request=RemoteCommands.route(List.of("/goal","completion","goal","--completion-json","{\"criteria\":[]}"),"project","session");
    assertEquals("PUT",request.method());assertEquals("/api/v1/projects/project/goals/goal/completion",request.path());
    assertTrue(request.body() instanceof com.fasterxml.jackson.databind.JsonNode);
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/goal","completion","goal","--completion-json","{}","--proof-json","{}"),"project","session"));
    for(String invalid:List.of("{} trailing","{\"criteria\":[],\"criteria\":[{}]}"))assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/goal","completion","goal","--completion-json",invalid),"project","session"));
  }
  @Test void modelCommandsHaveNamedRoutesAndRejectExtraArguments() {
    assertEquals("/api/v1/models",RemoteCommands.route(List.of("/models"),null,null).path());
    assertEquals("/api/v1/models/current",RemoteCommands.route(List.of("/model"),null,null).path());
    var selected=RemoteCommands.route(List.of("/model","fixture"),null,null);
    assertEquals("PUT",selected.method());assertEquals(Map.of("model","fixture"),selected.body());
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/models","extra"),null,null));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/model","fixture","extra"),null,null));
  }
  @Test void shellDenialAliasAndReconciliationRequireExplicitAcknowledgment() {
    assertEquals(Map.of("approved",false),RemoteCommands.route(List.of("/approval","deny","request"),"project","session").body());
    for(String command:List.of("/goal","/timer")) {
      var request=RemoteCommands.route(List.of(command,"reconcile","item","--run-id","run","--acknowledge-uncertain-side-effects"),"project","session");
      assertEquals(Map.of("expectedRunId","run","acknowledgeUncertainSideEffects",true),request.body());
      assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of(command,"reconcile","item","--run-id","run"),"project","session"));
    }
  }
  @Test void goalWaitingUsesObservedVersionAndShellOptionNames() {
    assertEquals("GET",RemoteCommands.route(List.of("/goal","wait","goal"),"project","session").method());
    assertEquals(Map.of("dependencyId","dependency","reason","blocked","sessionId","session"),RemoteCommands.route(List.of("/goal","wait","goal","--dependency-id","dependency","--wait-reason","blocked"),"project","session").body());
    for(String action:List.of("activate","resume","cancel")) {
      var request=RemoteCommands.route(List.of("/goal","wait-"+action,"goal","--wait-version","2"),"project","session");
      assertEquals("/api/v1/projects/project/goals/goal/wait/"+action,request.path());
      assertEquals(Map.of("expectedVersion",2L),request.body());
    }
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/goal","wait-resume","goal","--wait-version","0"),"project","session"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/goal","wait","goal","--dependency-id","first","--dependency-id","second"),"project","session"));
  }
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
