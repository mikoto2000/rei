package dev.mikoto2000.rei.core;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import dev.mikoto2000.rei.externalagent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GitChangeTestImpactTest {
  @Test void quotedPathAndHeaderLikeAddedTextCannotRedirectLineEvidence()throws Exception {
    var fake=new ExternalAgentProcessRunner(){
      @Override public Output run(List<String> command,Path directory,String input,Duration total,Duration idle,int bytes,java.util.function.BooleanSupplier cancelled){
        assertTrue(command.contains(":(literal)B.java"));
        return new Output(ExternalAgentResult.Status.SUCCESS,"diff --git a/quoted b/quoted\n+++ \"b/quoted\"\n@@ -1 +1 @@\n+++ b/B.java\n@@ -3 +3 @@\n+text\n","",0,0,false);
      }
    };
    assertTrue(new GitChangedFiles(fake).changedLines(root.toRealPath(),Set.of("quoted","B.java")).ranges().isEmpty());
  }
  @Test void gitCoverageObservesCurrentChangedLinesWithoutExecutingTests()throws Exception {
    init();write("A.java","class A {\n int value;\n}\n");
    write("coverage/lcov.info","TN:ATest\nSF:A.java\nDA:1,1\nDA:2,0\nDA:3,1\nend_of_record\n");
    var result=new ChangeTestImpactService(new RepositoryMapService()).analyzeGit(root,20,List.of("coverage/lcov.info"));
    assertEquals(List.of(1,2,3),result.coverage().areas().getFirst().lines().stream().map(CoverageImpactService.Line::number).toList());
    assertEquals("REPORTED_UNCOVERED",result.coverage().areas().getFirst().lines().get(1).status());
    assertTrue(result.warnings().stream().anyMatch(value->value.contains("Untracked")));
    var callback=Arrays.stream(org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(new Tools()).build().getToolCallbacks()).filter(c->c.getToolDefinition().name().equals("changeTestImpact")).findFirst().orElseThrow();
    try(var scope=dev.mikoto2000.rei.core.chat.AgentRunScope.open(new dev.mikoto2000.rei.core.chat.AgentRunContext("run","session",root))){
      var json=tools.jackson.databind.json.JsonMapper.builder().build().readTree(callback.call("{\"coverageReports\":[\"coverage/lcov.info\"]}"));
      assertEquals("REPORT_OBSERVATIONS",json.get("coverage").get("status").asString());
    }
  }
  @TempDir Path root;
  private final ExternalAgentProcessRunner processes=new ExternalAgentProcessRunner();
  private void git(String... args) {
    var command=new ArrayList<String>(List.of("git"));command.addAll(List.of(args));
    var output=processes.run(command,root,"",Duration.ofSeconds(5),Duration.ofSeconds(5),65536,()->false);
    assertEquals(ExternalAgentResult.Status.SUCCESS,output.status(),output.stderr());
  }
  private void init() throws Exception {
    git("init");write("A.java","class A {}");write("Deleted.java","class Deleted {}");
    git("add",".");git("-c","user.name=Fixture","-c","user.email=fixture@example.invalid","commit","-m","base");
  }
  private void write(String name,String text)throws Exception {
    Path file=root.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,text);
  }
  @Test void collectsStagedUnstagedDeletedAndUntrackedPathsWithoutChangingIndex()throws Exception {
    init();write("A.java","class A { int x; }");write("Staged.java","class Staged {}");git("add","Staged.java");
    Files.delete(root.resolve("Deleted.java"));write("日本語 space.java","class Other {}");
    write("src/test/java/ATest.java","class ATest {}");
    var result=new ChangeTestImpactService(new RepositoryMapService()).analyzeGit(root,20);
    assertEquals(List.of("A.java","Deleted.java","Staged.java","src/test/java/ATest.java","日本語 space.java"),result.changedFiles());
    assertTrue(result.unindexedChanges().contains("Deleted.java"));assertTrue(result.partial());
    assertTrue(result.candidates().stream().anyMatch(c->c.path().equals("src/test/java/ATest.java")&&c.testCandidate()));
    git("diff","--cached","--exit-code","--","A.java");
    assertEquals("class A { int x; }",Files.readString(root.resolve("A.java")));
  }
  @Test void cleanRepositoryReturnsEmptyObservationAndExcludedPathsAreNotExposed()throws Exception {
    init();var service=new ChangeTestImpactService(new RepositoryMapService());
    assertTrue(service.analyzeGit(root,20).changedFiles().isEmpty());
    write(".env","private fixture");write(".aws/credentials","private fixture");
    var result=service.analyzeGit(root,20);
    assertTrue(result.changedFiles().isEmpty());assertTrue(result.partial());
    assertTrue(result.warnings().stream().anyMatch(w->w.contains("Excluded")));
    assertFalse(result.warnings().toString().contains("private fixture"));
  }
  @Test void renameIncludesOldAndNewAndTooManyChangesAreRejected()throws Exception {
    init();git("mv","A.java","Renamed.java");
    var service=new ChangeTestImpactService(new RepositoryMapService());
    assertEquals(List.of("A.java","Renamed.java"),service.analyzeGit(root,20).changedFiles());
    for(int i=0;i<65;i++)write("New"+i+".java","class New"+i+" {}");
    assertThrows(java.io.IOException.class,()->service.analyzeGit(root,20));
  }
  @Test void nonRepositoryAndNestedRootAreRejected()throws Exception {
    var service=new ChangeTestImpactService(new RepositoryMapService());
    assertThrows(java.io.IOException.class,()->service.analyzeGit(root,20));
    init();Files.createDirectory(root.resolve("nested"));
    assertThrows(java.io.IOException.class,()->service.analyzeGit(root.resolve("nested"),20));
  }
  @Test void stagedChangeIsRetainedWhenWorktreeMatchesHeadAndIgnoredFilesStayExcluded()throws Exception {
    init();write("A.java","class A { int staged; }");git("add","A.java");write("A.java","class A {}");
    write(".gitignore","ignored.java\n");git("add",".gitignore");write("ignored.java","class Ignored {}");
    assertEquals(List.of(".gitignore","A.java"),new ChangeTestImpactService(new RepositoryMapService()).analyzeGit(root,20).changedFiles());
  }
  @Test void collectionRejectsIncompleteOutputAndPropagatesCancellation()throws Exception {
    for(var status:List.of(ExternalAgentResult.Status.FAILED,ExternalAgentResult.Status.TOTAL_TIMEOUT,ExternalAgentResult.Status.SUCCESS)) {
      var fake=new ExternalAgentProcessRunner(){
        @Override public Output run(List<String> command,Path directory,String input,Duration total,Duration idle,int bytes,java.util.function.BooleanSupplier cancelled) {
          return new Output(status,"", "",1,0,status==ExternalAgentResult.Status.SUCCESS);
        }
      };
      assertThrows(java.io.IOException.class,()->new GitChangedFiles(fake).collect(root.toRealPath()));
    }
    var cancelled=new ExternalAgentProcessRunner(){
      @Override public Output run(List<String> command,Path directory,String input,Duration total,Duration idle,int bytes,java.util.function.BooleanSupplier check) {
        return new Output(ExternalAgentResult.Status.CANCELLED,"","",null,0,false);
      }
    };
    assertThrows(java.util.concurrent.CancellationException.class,()->new GitChangedFiles(cancelled).collect(root.toRealPath()));
    var malformed=new ExternalAgentProcessRunner(){
      @Override public Output run(List<String> command,Path directory,String input,Duration total,Duration idle,int bytes,java.util.function.BooleanSupplier check) {
        String output=command.contains("rev-parse")?directory+"\n"+"a".repeat(40)+"\n":"partial.java";
        return new Output(ExternalAgentResult.Status.SUCCESS,output,"",0,0,false);
      }
    };
    assertThrows(java.io.IOException.class,()->new GitChangedFiles(malformed).collect(root.toRealPath()));
  }
  @Test void omittedToolArgumentUsesCapturedProjectAndRetainsReadPermission()throws Exception {
    init();write("A.java","class A { int changed; }");
    var callback=Arrays.stream(org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(new Tools()).build().getToolCallbacks())
        .filter(c->c.getToolDefinition().name().equals("changeTestImpact")).findFirst().orElseThrow();
    var schema=tools.jackson.databind.json.JsonMapper.builder().build().readTree(callback.getToolDefinition().inputSchema());
    assertTrue(schema.get("required")==null||!schema.get("required").toString().contains("changedFiles"));
    try(var scope=dev.mikoto2000.rei.core.chat.AgentRunScope.open(new dev.mikoto2000.rei.core.chat.AgentRunContext("run","session",root))) {
      String response=callback.call("{}");
      var result=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response);
      assertEquals("A.java",result.get("changedFiles").get(0).asString());
      assertThrows(IllegalArgumentException.class,()->new Tools().changeTestImpact(List.of(),20));
    }
    var policy=new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null));
    assertEquals(Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ),policy.capabilities("changeTestImpact"));
    new dev.mikoto2000.rei.subagent.SubAgentToolPolicy(Set.of("changeTestImpact")).validate(List.of("changeTestImpact"));
  }
}
