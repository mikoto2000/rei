package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.TextChangeSetService;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("integration")
class ExternalFixProposalTest {
  @TempDir Path root;
  ExternalAgentResult success(TextChangeSetService.Request draft) {
    return new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS,"reviewed",List.of(),List.of(),1,0,"private logs",null,null,draft,null);
  }
  TextChangeSetService changes() {
    var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("changes.db"));
    return new TextChangeSetService(new dev.mikoto2000.rei.core.TextChangeSetRepository(source));
  }
  @Test void reviewedFixIsSavedWithoutWritingAndExplicitApplyCanBeReReviewedInANewRun() throws Exception {
    var fixture=new ExternalReviewHistoryTest();fixture.root=root;var history=fixture.repository();var changes=changes();
    String project=UUID.randomUUID().toString();var owner=new dev.mikoto2000.rei.core.project.ProjectContext(project,"Project",root);
    var file=Files.writeString(root.resolve("note.txt"),"private baseline\r\n");var requests=new ArrayList<ExternalAgentRequest>();
    var service=fixture.service((request,cancelled)->{requests.add(request);return success(request.action()==ExternalAgentRequest.Action.PROPOSE_FIX?
        new TextChangeSetService.Request("note.txt","private baseline\r\n","reviewed replacement\r\n"):null);},history);
    service.changeSets(changes);
    var first=service.review(fixture.run("Codex にレビューして",project),"review","note.txt",null);
    var foreign=fixture.run("Codex に修正案を提案して",UUID.randomUUID().toString());
    assertEquals(ExternalAgentResult.Status.REJECTED,service.proposeFix(foreign,first.reviewId(),"fix",null).status());assertFalse(foreign.externalDelegationUsed());
    var reviewOnly=fixture.run("Codex にレビューして",project);
    assertEquals(ExternalAgentResult.Status.REJECTED,service.proposeFix(reviewOnly,first.reviewId(),"make a fix",null).status());assertFalse(reviewOnly.externalDelegationUsed());
    var run=fixture.run("Codex にレビューの修正案を提案して",project);
    var proposal=service.proposeFix(run,first.reviewId(),"prepare a minimal fix",null);
    assertTrue(proposal.success());assertNotNull(proposal.changeSetId());assertNull(proposal.proposedChange());assertEquals("",proposal.rawOutput());
    assertEquals("private baseline\r\n",Files.readString(file));assertEquals(first.reviewId(),history.get(project,proposal.reviewId()).previousId());
    assertEquals(proposal.changeSetId(),fixture.repository().get(project,proposal.reviewId()).result().changeSetId());
    assertNull(fixture.repository().get(project,proposal.reviewId()).result().proposedChange());
    assertEquals(ExternalAgentResult.Status.REJECTED,service.rereview(run,proposal.reviewId(),"again",null).status());
    var saved=changes.inspect(owner,proposal.changeSetId());assertEquals("PROPOSED",saved.status());assertTrue(saved.diff().contains("reviewed replacement"));
    assertEquals("APPLIED",changes.apply(owner,saved.id(),saved.proposalSha256(),(p,o,n)->Files.writeString(p,n)).status());
    var rerun=service.rereview(fixture.run("Codex に再レビューして",project),proposal.reviewId(),"check current changes",null);
    assertTrue(rerun.success());assertEquals("reviewed replacement\r\n",Files.readString(requests.getLast().target()));
    assertEquals(3,requests.size());
  }
  @Test void outOfTargetAndStaleProposalsNeverWriteOrBecomeSuccessfulChangeSets() throws Exception {
    var fixture=new ExternalReviewHistoryTest();fixture.root=root;var history=fixture.repository();var changes=changes();String project=UUID.randomUUID().toString();
    var file=Files.writeString(root.resolve("note.txt"),"before");Files.writeString(root.resolve("other.txt"),"other");
    var draft=new java.util.concurrent.atomic.AtomicReference<>(new TextChangeSetService.Request("other.txt","other","after"));
    var service=fixture.service((request,cancelled)->success(request.action()==ExternalAgentRequest.Action.PROPOSE_FIX?draft.get():null),history);service.changeSets(changes);
    var first=service.review(fixture.run("Codex にレビューして",project),"review","note.txt",null);
    for(var request:List.of(draft.get(),new TextChangeSetService.Request("note.txt","stale baseline","after"),new TextChangeSetService.Request("../outside","before","after"))) {
      draft.set(request);var result=service.proposeFix(fixture.run("Codex に修正案を提案して",project),first.reviewId(),"fix",null);
      assertEquals(ExternalAgentResult.Status.FAILED,result.status());assertNull(result.changeSetId());assertNull(result.proposedChange());
    }
    assertEquals("before",Files.readString(file));assertEquals("other",Files.readString(root.resolve("other.txt")));
  }
  @Test void fakeSuccessfulOutputAfterRunCancellationCannotStageAProposal() throws Exception {
    var fixture=new ExternalReviewHistoryTest();fixture.root=root;var history=fixture.repository();String project=UUID.randomUUID().toString();
    Files.writeString(root.resolve("note.txt"),"before");var current=new java.util.concurrent.atomic.AtomicReference<dev.mikoto2000.rei.core.stagnation.RunExecutionContext>();
    var service=fixture.service((request,cancelled)->{if(request.action()==ExternalAgentRequest.Action.PROPOSE_FIX){current.get().cancel();return success(new TextChangeSetService.Request("note.txt","before","after"));}return success(null);},history);service.changeSets(changes());
    var first=service.review(fixture.run("Codex にレビューして",project),"review","note.txt",null);
    current.set(fixture.run("Codex に修正案を提案して",project));
    assertThrows(java.util.concurrent.CancellationException.class,()->service.proposeFix(current.get(),first.reviewId(),"fix",null));
    assertEquals("before",Files.readString(root.resolve("note.txt")));
    assertNull(history.list(project).stream().filter(r->r.status().equals("CANCELLED")).findFirst().orElseThrow().result().changeSetId());
  }
  @Test void proposalGateRejectsQuotedNegativeAndToolOnlyRequestsAndPolicyDoesNotGrantReadAccess() {
    for(String text:List.of("Codex に修正案を提案して","Please ask Codex to propose a fix","Codex にレビューして修正案も提案して"))assertTrue(ExternalAgentAuthorization.explicitFixProposalRequest(text),text);
    for(String text:List.of("Codex にレビューして","修正案を提案して","Codex に修正案を出さないで","「Codex に修正案を提案して」を英訳して","Codex fix proposal"))assertFalse(ExternalAgentAuthorization.explicitFixProposalRequest(text),text);
    var policy=new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(false,null,null,null));
    assertTrue(policy.capabilities("requestCodexFixProposal").contains(dev.mikoto2000.rei.core.policy.ActionCapability.EXECUTE));
    assertTrue(policy.capabilities("requestCodexFixProposal").contains(dev.mikoto2000.rei.core.policy.ActionCapability.LOCAL_WRITE));
    assertThrows(IllegalArgumentException.class,()->new dev.mikoto2000.rei.subagent.SubAgentToolPolicy(Set.of("requestCodexFixProposal")).validate(List.of("requestCodexFixProposal")));
  }
  @Test void cliFixProposalUsesReadOnlySchemaAndInvalidOutputFailsClosed() throws Exception {
    var properties=new CodexProperties();properties.setCommand("test-codex");var schema=new java.util.concurrent.atomic.AtomicReference<Path>();
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var draft=mapper.createObjectNode();draft.put("summary","drafted");draft.putArray("findings");draft.putArray("warnings");
    draft.putObject("proposal").put("path","note.txt").put("expectedText","before\r\n").put("replacement","after\r\n");
    var output=new java.util.concurrent.atomic.AtomicReference<>(draft.toString());
    var runner=new ExternalAgentProcessRunner(){
      @Override public Output run(List<String> command,Path cwd,String input,java.time.Duration total,java.time.Duration idle,int limit,java.util.function.BooleanSupplier cancelled){
        if(command.contains("--help"))return new Output(ExternalAgentResult.Status.SUCCESS,"--ignore-user-config --ignore-rules --strict-config --ephemeral --output-schema --json","",0,1,false);
        assertTrue(command.contains("--ephemeral"));assertTrue(command.contains("default_permissions=\"rei_review\""));
        assertTrue(input.contains("fix proposal"));assertTrue(input.contains("Do not modify"));
        schema.set(Path.of(command.get(command.indexOf("--output-schema")+1)));
        try{assertTrue(Files.readString(schema.get()).contains("expectedText"));
          var event=mapper.createObjectNode();event.put("type","item.completed");event.putObject("item").put("type","agent_message").put("text",output.get());
          return new Output(ExternalAgentResult.Status.SUCCESS,event.toString(),"",0,1,false);
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
      }
    };
    var executor=new CodexExternalAgentExecutor(properties,runner);
    var request=new ExternalAgentRequest(ExternalAgentRequest.Agent.CODEX,ExternalAgentRequest.Action.PROPOSE_FIX,"fix",root,null,"","run","id");
    var result=executor.execute(request,()->false);assertTrue(result.success());assertEquals("before\r\n",result.proposedChange().expectedText());
    assertNull(result.forEvaluation().proposedChange());assertFalse(Files.exists(schema.get()));
    draft.remove("proposal");output.set(draft.toString());assertEquals(ExternalAgentResult.Status.FAILED,executor.execute(request,()->false).status());
    assertThrows(IllegalArgumentException.class,()->ExternalAgentCommandRequest.parse("/agent codex propose_fix note.txt"));
  }
}
