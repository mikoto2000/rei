package dev.mikoto2000.rei.core;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import dev.mikoto2000.rei.core.project.ProjectContext;

@org.junit.jupiter.api.Tag("integration")
class MultiHunkChangeSetTest {
  @TempDir Path root;
  final String projectId=UUID.randomUUID().toString();
  ProjectContext project(){return new ProjectContext(projectId,"test",root);}
  TextChangeSetService service(){return new TextChangeSetService(new TextChangeSetRepository(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("changes.db"))));}
  TextChangeSetService.Request request(TextChangeSetService service,List<TextChangeSetService.Edit> edits)throws Exception{return new TextChangeSetService.Request("a.txt",null,null,service.readBase(project(),"a.txt").sha256(),edits);}
  @Test void multipleHunksUseTheOriginalVersionAndExplicitApplyIsOneShot()throws Exception {
    Files.writeString(root.resolve("a.txt"),"abcd");var service=service();
    var proposal=service.propose(project(),request(service,List.of(new TextChangeSetService.Edit("ab","cd"),new TextChangeSetService.Edit("cd","ef"))));
    assertEquals("abcd",Files.readString(root.resolve("a.txt")));assertTrue(proposal.diff().contains("+cdef"));
    assertEquals("APPLIED",service.apply(project(),proposal.id(),proposal.proposalSha256(),TextDocumentTransaction::writeSingle).status());
    assertEquals("cdef",Files.readString(root.resolve("a.txt")));
    assertEquals("APPLIED",service().apply(project(),proposal.id(),proposal.proposalSha256(),(p,o,n)->fail("no replay")).status());
  }
  @Test void ambiguousMissingOverlappingAndStaleEditsNeverModifyTheFile()throws Exception {
    Files.writeString(root.resolve("a.txt"),"abcd abcd");var service=service();
    assertThrows(IllegalArgumentException.class,()->service.propose(project(),request(service,List.of(new TextChangeSetService.Edit("ab","x")))));
    assertThrows(IllegalArgumentException.class,()->service.propose(project(),request(service,List.of(new TextChangeSetService.Edit("missing","x")))));
    assertThrows(IllegalArgumentException.class,()->service.propose(project(),request(service,List.of(new TextChangeSetService.Edit("","x")))));
    Files.writeString(root.resolve("a.txt"),"abcd");var overlapping=request(service,List.of(new TextChangeSetService.Edit("abc","x"),new TextChangeSetService.Edit("bcd","y")));
    assertThrows(IllegalArgumentException.class,()->service.propose(project(),overlapping));
    var stale=request(service,List.of(new TextChangeSetService.Edit("ab","x")));Files.writeString(root.resolve("a.txt"),"human");
    assertThrows(IllegalArgumentException.class,()->service.propose(project(),stale));assertEquals("human",Files.readString(root.resolve("a.txt")));
  }
  @Test void deletionMultilineJapaneseBomAndLineEndingsArePreserved()throws Exception {
    for(var newline:List.of("\n","\r\n")){
      var text="\ufeff日本語"+newline+"remove"+newline+"last";Files.writeString(root.resolve("a.txt"),text);var service=service();
      var proposal=service.propose(project(),request(service,List.of(new TextChangeSetService.Edit("remove"+newline,""),new TextChangeSetService.Edit("日本語","変更"))));
      service.apply(project(),proposal.id(),proposal.proposalSha256(),TextDocumentTransaction::writeSingle);
      assertArrayEquals(("\ufeff変更"+newline+"last").getBytes(java.nio.charset.StandardCharsets.UTF_8),Files.readAllBytes(root.resolve("a.txt")));
    }
  }
  @Test void discardAndLegacyFullReplacementRemainCompatible()throws Exception {
    Files.writeString(root.resolve("a.txt"),"old");var service=service();
    var legacy=service.propose(project(),new TextChangeSetService.Request("a.txt","old","legacy"));assertEquals("DISCARDED",service.discard(project(),legacy.id(),legacy.proposalSha256()).status());
    var partial=service.propose(project(),request(service,List.of(new TextChangeSetService.Edit("old","new"))));
    assertEquals("DISCARDED",service.discard(project(),partial.id(),partial.proposalSha256()).status());
    assertThrows(IllegalStateException.class,()->service.apply(project(),partial.id(),partial.proposalSha256(),TextDocumentTransaction::writeSingle));assertEquals("old",Files.readString(root.resolve("a.txt")));
    assertEquals(Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.LOCAL_WRITE),dev.mikoto2000.rei.core.policy.ToolPermissionPolicy.intrinsicCapabilities("proposeTextChangeSet"));
  }
  @Test void failedAtomicReplaceAndLastMomentExternalChangePreserveTarget()throws Exception {
    var file=root.resolve("a.txt");Files.writeString(file,"old");
    assertThrows(java.io.IOException.class,()->TextDocumentTransaction.writeSingle(file,"old","new",(staged,target)->{throw new java.io.IOException("injected move failure");}));
    assertEquals("old",Files.readString(file));
    var service=service();var proposal=service.propose(project(),request(service,List.of(new TextChangeSetService.Edit("old","new"))));
    assertThrows(java.io.IOException.class,()->service.apply(project(),proposal.id(),proposal.proposalSha256(),(path,before,after)->{Files.writeString(path,"human");TextDocumentTransaction.writeSingle(path,before,after);}));
    assertEquals("human",Files.readString(file));assertEquals("FAILED_UNCERTAIN",service.inspect(project(),proposal.id()).status());
    assertThrows(IllegalStateException.class,()->service().apply(project(),proposal.id(),proposal.proposalSha256(),(p,o,n)->fail("no replay")));
  }
  @Test void versionsAndInputModesAreStrictAndHunkCountIsBounded()throws Exception {
    Files.writeString(root.resolve("a.txt"),"old");var service=service();var version=service.readBase(project(),"a.txt").sha256();
    var edits=List.of(new TextChangeSetService.Edit("old","new"));
    assertThrows(IllegalArgumentException.class,()->service.propose(project(),new TextChangeSetService.Request("a.txt","old","new",version,edits)));
    assertThrows(IllegalArgumentException.class,()->service.propose(project(),new TextChangeSetService.Request("a.txt",null,null,null,edits)));
    assertThrows(IllegalArgumentException.class,()->service.propose(project(),new TextChangeSetService.Request("a.txt",null,null,version,Collections.nCopies(65,edits.getFirst()))));
  }
  @Test void atomicWritingPreservesPermissionsAndPolicyDenialStillPrecedesCallback()throws Exception {
    var file=root.resolve("a.txt");Files.writeString(file,"old");
    var acl=Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class);var posix=Files.getFileAttributeView(file,java.nio.file.attribute.PosixFileAttributeView.class);
    var originalAcl=acl==null?null:acl.getAcl();var originalPermissions=posix==null?null:posix.readAttributes().permissions();
    TextDocumentTransaction.writeSingle(file,"old","new");
    if(originalAcl!=null)assertEquals(originalAcl,Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class).getAcl());
    if(originalPermissions!=null)assertEquals(originalPermissions,Files.getPosixFilePermissions(file));
    var policy=new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.LOCAL_WRITE),null));
    var guard=new dev.mikoto2000.rei.core.policy.ToolPermissionGuard(policy,new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),new dev.mikoto2000.rei.event.InMemoryAgentEventBus());
    assertThrows(dev.mikoto2000.rei.core.policy.ToolPermissionException.class,()->guard.check("proposeTextChangeSet","{\"baseVersion\":\"version\",\"edits\":[]}",null));
    assertThrows(dev.mikoto2000.rei.core.policy.ToolPermissionException.class,()->guard.check("applyTextChangeSet",null));
    assertEquals("new",Files.readString(file));
  }
  @Test void partialDiffShowsTheChangedRegionWithoutDumpingUnchangedFile()throws Exception {
    var text=new StringBuilder();for(int i=0;i<500;i++)text.append(i==350?"int timeout = 30;\n":"unchanged line "+i+"\n");
    Files.writeString(root.resolve("a.txt"),text);var service=service();
    var proposal=service.propose(project(),request(service,List.of(new TextChangeSetService.Edit("int timeout = 30;","int timeout = 60;"))));
    assertTrue(proposal.diff().contains("-int timeout = 30;"));assertTrue(proposal.diff().contains("+int timeout = 60;"));
    assertFalse(proposal.diff().contains("unchanged line 0\n"));assertFalse(proposal.diff().contains("unchanged line 499\n"));
    assertEquals(text.toString().replace("int timeout = 30;","int timeout = 60;"),new String(service.exportProposal(project(),proposal.id()),java.nio.charset.StandardCharsets.UTF_8));
  }
  @Test void existingToolCallbackAcceptsPartialJsonWithoutWholeFileFields()throws Exception {
    Files.writeString(root.resolve("a.txt"),"old");var service=service();var tools=new Tools();tools.setTextChangeSets(service);
    var callback=Arrays.stream(org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks()).filter(tool->tool.getToolDefinition().name().equals("proposeTextChangeSet")).findFirst().orElseThrow();
    var input="{\"request\":{\"path\":\"a.txt\",\"baseVersion\":\""+service.readBase(project(),"a.txt").sha256()+"\",\"edits\":[{\"oldText\":\"old\",\"newText\":\"new\"}]}}";
    try(var scope=dev.mikoto2000.rei.core.chat.AgentRunScope.open(new dev.mikoto2000.rei.core.chat.AgentRunContext("run","session",root,projectId))){
      var result=new com.fasterxml.jackson.databind.ObjectMapper().readTree(callback.call(input));assertEquals("PROPOSED",result.get("status").asText());assertTrue(result.get("diff").asText().contains("+new"));
    }
    assertEquals("old",Files.readString(root.resolve("a.txt")));
  }
}
