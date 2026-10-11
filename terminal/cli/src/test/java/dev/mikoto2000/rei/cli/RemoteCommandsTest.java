package dev.mikoto2000.rei.cli;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
class RemoteCommandsTest {
  @Test void researchRequestsUseOnlyTheirNamedDomainRoutes() {
    assertEquals("/api/v1/paper/operations",RemoteCommands.route(List.of("/paper","search","graph theory","--limit","2"),"project","session").path());
    assertEquals("/api/v1/material-reviews",RemoteCommands.route(List.of("/material-review","beginner","--entry","index.html"),"project","session").path());
    var agent=RemoteCommands.route(List.of("/agent","codex","review","src/main"),"project","session");
    assertEquals("/api/v1/external-agent/operations",agent.path());
    assertEquals(Map.of("projectId","project","sessionId","session","agent","codex","action","review","target","src/main"),agent.body());
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/paper","execute","anything"),"project","session"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/agent","arbitrary","execute","anything"),"project","session"));
  }
  @Test void recoveryHasSeparateNamedRoutesAndRequiresTheApprovedHash() {
    String id="12345678-1234-1234-1234-123456789abc",hash="a".repeat(64);
    var document=RemoteCommands.route(List.of("/document","rollback",id,hash),"project","session");
    assertEquals("/api/v1/document/recovery",document.path());
    assertEquals(Map.of("projectId","project","sessionId","session","action","rollback","id",id,"hash",hash),document.body());
    assertEquals("/api/v1/repair/recovery",RemoteCommands.route(List.of("/repair","show",id),"project","session").path());
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/document","execute",id,hash),"project","session"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/repair","apply",id),"project","session"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/document","show",id,hash),"project","session"));
  }
  @Test void historyPreservesSessionPaginationLegacyRangesAndSearchScopes() {
    assertEquals("/api/v1/history/report",RemoteCommands.route(List.of("/history"),"project","session").path());
    assertEquals("/api/v1/history/report?limit=5&projectId=other&cursor=next",RemoteCommands.route(List.of("/history","--limit","5","--project-id","other","--cursor","next"),"project","session").path());
    assertEquals("/api/v1/history/conversations?selectedProjectId=project&limit=50&offset=0",RemoteCommands.route(List.of("/history","list"),"project","session").path());
    assertEquals("/api/v1/history/show?selectedProjectId=project",RemoteCommands.route(List.of("/history","show"),"project","session").path());
    assertEquals("/api/v1/history/show?selectedProjectId=project&conversation=chosen&limit=10&cursor=next",RemoteCommands.route(List.of("/history","show","chosen","--limit","10","--cursor","next"),"project","session").path());
    assertEquals("/api/v1/history/conversation?selectedProjectId=project&sessionId=session&last=20&all=false",RemoteCommands.route(List.of("/history","show","--last","20"),"project","session").path());
    assertEquals("/api/v1/history/search?selectedProjectId=project&query=two%20words&scope=ALL_PROJECTS&limit=10",RemoteCommands.route(List.of("/history","search","two","words","--all"),"project","session").path());
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/history","show","--last","10","--all"),"project","session"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/history","show","--cursor","next"),"project","session"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/history","show","id","--limit","5","--all"),"project","session"));
  }
  @Test void timingDefaultsAndArgumentOrderMatchShell() {
    assertEquals("/api/v1/timing?runId=last&details=false&projectId=project&sessionId=session",RemoteCommands.route(List.of("/timing"),"project","session").path());
    assertEquals("/api/v1/timing?runId=run&details=true&projectId=project&sessionId=session",RemoteCommands.route(List.of("/timing","--details","run"),"project","session").path());
    assertEquals("/api/v1/timing?runId=last&details=false",RemoteCommands.route(List.of("/timing"),null,null).path());
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/timing","--details","--details"),"project","session"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/timing","one","two"),"project","session"));
  }
  @Test void profileAndBriefingHaveAllLegacyReadForms() {
    assertEquals("/api/v1/profile/report",RemoteCommands.route(List.of("/profile"),null,null).path());
    assertEquals("/api/v1/profile/report",RemoteCommands.route(List.of("/profile","summary"),null,null).path());
    assertEquals("/api/v1/profile/path",RemoteCommands.route(List.of("/profile","path"),null,null).path());
    assertEquals("/api/v1/profile/chart?bucketSeconds=60&width=40",RemoteCommands.route(List.of("/profile","chart"),null,null).path());
    assertEquals("/api/v1/profile/chart?bucketSeconds=5&width=10",RemoteCommands.route(List.of("/profile","chart","--width","10","--bucket-seconds","5"),null,null).path());
    assertEquals("/api/v1/profile/mermaid?limit=12",RemoteCommands.route(List.of("/profile","mermaid"),null,null).path());
    assertEquals("/api/v1/briefing",RemoteCommands.route(List.of("/briefing","today"),null,null).path());
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/profile","path","unexpected"),null,null));
  }
  @Test void doctorChecksRemainExplicitBoundedAndDeduplicated() {
    assertEquals("/api/v1/doctor?details=false",RemoteCommands.route(List.of("/doctor"),null,null).path());
    assertEquals("/api/v1/doctor?details=true",RemoteCommands.route(List.of("/doctor","--details"),null,null).path());
    var active=RemoteCommands.route(List.of("/doctor","--check","connectivity","--details","--check=CODEX","--check","connectivity"),"project","session");
    assertEquals("/api/v1/diagnostics",active.path());
    assertEquals(List.of("connectivity","codex"),((Map<?,?>)active.body()).get("checks"));
    assertEquals("session",((Map<?,?>)active.body()).get("sessionId"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/doctor","--check","arbitrary"),"project","session"));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/doctor","--check","inference"),null,null));
  }
  @Test void checkpointAndResumeAcceptTheLegacyShellGrammar() {
    var saved=RemoteCommands.route(List.of("/checkpoint"),"project","session");
    assertEquals("POST",saved.method());assertEquals("/api/v1/projects/project/checkpoints",saved.path());assertEquals(Map.of("sessionId","session"),saved.body());
    assertEquals("/api/v1/projects/project/checkpoints",RemoteCommands.route(List.of("/resume"),"project",null).path());
    assertEquals("/api/v1/projects/project/checkpoints",RemoteCommands.route(List.of("/resume","list"),"project",null).path());
    assertEquals("/api/v1/projects/project/checkpoints/task",RemoteCommands.route(List.of("/resume","show","task"),"project",null).path());
    assertEquals("/api/v1/projects/project/checkpoints/task/abandon",RemoteCommands.route(List.of("/resume","abandon","task"),"project",null).path());
    assertEquals("/api/v1/projects/project/checkpoints/task/resume",RemoteCommands.route(List.of("/resume","task"),"project",null).path());
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/checkpoint"),"project",null));
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/resume","show","task","extra"),"project",null));
  }
  @Test void memoryReadsAndForgetUseSelectedProjectAndShellPagination() {
    assertEquals("/api/v1/projects/project/memory?limit=20&offset=0",RemoteCommands.route(List.of("/memory","list"),"project",null).path());
    assertEquals("/api/v1/projects/project/memory?limit=5&offset=10",RemoteCommands.route(List.of("/memory","list","--offset","10","--limit","5"),"project",null).path());
    assertEquals("/api/v1/projects/project/memory/search?query=two%20words&limit=10",RemoteCommands.route(List.of("/memory","search","two","words"),"project",null).path());
    assertEquals("/api/v1/projects/project/memory/item",RemoteCommands.route(List.of("/memory","show","item"),"project",null).path());
    assertEquals("/api/v1/projects/project/memory/item/forget",RemoteCommands.route(List.of("/memory","forget","item"),"project",null).path());
    assertThrows(IllegalArgumentException.class,()->RemoteCommands.route(List.of("/memory","search","query","--unknown"),"project",null));
  }
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
    assertEquals("/api/v1/history/show?selectedProjectId=project",RemoteCommands.route(List.of("/history","show"),"project","session").path());
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
