package dev.mikoto2000.rei.core;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.process.BuildTestFailureDiagnosis;
import dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner;
import static org.junit.jupiter.api.Assertions.*;

class SelfPatchReviewGitTest {
  @TempDir Path root;
  final AtomicInteger tests=new AtomicInteger();
  @BeforeEach void repository() throws Exception {
    git("init","--quiet");git("config","core.autocrlf","false");Files.writeString(root.resolve(".gitignore"),"build/\n");Files.writeString(root.resolve("A.txt"),"before\n");
    git("add","--",".gitignore","A.txt");git("commit","--quiet","--no-gpg-sign","-m","fixture");
  }
  void git(String... arguments)throws Exception {
    var command=new ArrayList<String>(List.of("git","--no-pager","-c","user.name=Fixture","-c","user.email=fixture@example.invalid","-c","core.autocrlf=false","-c","core.fsmonitor=false"));
    command.addAll(List.of(arguments));var process=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
    if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();fail("fixture git timed out");}
    assertEquals(0,process.exitValue(),new String(process.getInputStream().readNBytes(4096)));
  }
  SelfPatchReviewService service() {
    var inspector=new GitPatchInspector(new ExternalAgentProcessRunner());
    return new SelfPatchReviewService(inspector::capture,inspector::review,(r,c,t)->{
      tests.incrementAndGet();return new SelfPatchReviewService.TestObservation("completed",0,false,false,
          BuildTestFailureDiagnosis.command("completed",0,false,"","",null));});
  }
  SelfPatchReviewService.Result verify()throws Exception{return service().verify(root,new SelfPatchReviewService.Request("controlled fixture",10));}
  @Test void savedChangeSetRepairsRealGitFindingThenRunsFinalRealShellTest() throws Exception {
    Files.createDirectories(root.resolve("build"));Files.writeString(root.resolve("A.txt"),"after \n");
    var project=new dev.mikoto2000.rei.core.project.ProjectContext(UUID.randomUUID().toString(),"fixture",root);
    var projects=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    org.mockito.Mockito.when(projects.currentContext()).thenReturn(project);org.mockito.Mockito.when(projects.currentProject()).thenReturn(root);
    var tools=new Tools(projects,new dev.mikoto2000.rei.core.service.SystemShellService());
    var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("build/changes.db"));
    tools.setTextChangeSets(new TextChangeSetService(new TextChangeSetRepository(ds)));
    var proposal=tools.proposeTextChangeSet(new TextChangeSetService.Request("A.txt","after \n","after\n"));
    byte[] index=Files.readAllBytes(root.resolve(".git/index"));
    String command=System.getProperty("os.name").toLowerCase().contains("win")?
        "Add-Content build/runs.txt 'run'; exit 0":"printf '%s\\n' run >> build/runs.txt";
    var result=tools.selfRepairPatch(new SelfPatchRepairService.Request(command,10,List.of(new SelfPatchRepairService.Repair(proposal.id(),proposal.proposalSha256()))));
    assertEquals("VERIFIED_CHECKS",result.status(),result.toString());assertEquals(2,result.rounds().size());
    assertEquals("FIX_REQUIRED",result.rounds().getFirst().status());assertEquals("APPLIED",result.repairs().getFirst().status());
    assertEquals("after\n",Files.readString(root.resolve("A.txt")));assertEquals(3,Files.readAllLines(root.resolve("build/runs.txt")).size());
    assertArrayEquals(index,Files.readAllBytes(root.resolve(".git/index")));
  }
  @Test void realCommandsRunTwiceOnTheSamePatchAndIgnoredOutputsDoNotInvalidateIt() throws Exception {
    Files.writeString(root.resolve("A.txt"),"after\n");byte[] index=Files.readAllBytes(root.resolve(".git/index"));
    String command=System.getProperty("os.name").toLowerCase().contains("win")?
        "New-Item -ItemType Directory -Force build | Out-Null; Add-Content build/runs.txt 'run'; exit 0":
        "mkdir -p build; printf '%s\\n' run >> build/runs.txt";
    var result=new SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService()).verify(root,new SelfPatchReviewService.Request(command,10));
    assertEquals("VERIFIED_CHECKS",result.status(),result.toString());assertEquals(2,Files.readAllLines(root.resolve("build/runs.txt")).size());
    assertArrayEquals(index,Files.readAllBytes(root.resolve(".git/index")));
  }
  @Test void trackedWhitespaceRequiresFixAndACompleteNewCycleAfterFix() throws Exception {
    Files.writeString(root.resolve("A.txt"),"after \n");var first=verify();
    assertEquals("FIX_REQUIRED",first.status());assertTrue(first.review().findings().stream().anyMatch(f->f.kind().equals("WHITESPACE")));assertEquals(1,tests.get());
    Files.writeString(root.resolve("A.txt"),"after\n");assertEquals("VERIFIED_CHECKS",verify().status());assertEquals(3,tests.get());
  }
  @Test void untrackedConflictMarkersAreReviewedWithoutAddingFilesToTheIndex() throws Exception {
    byte[] index=Files.readAllBytes(root.resolve(".git/index"));
    Files.writeString(root.resolve("New.txt"),"<<<<<<< local\nleft\n=======\nright\n>>>>>>> incoming\n");
    var result=verify();assertEquals("FIX_REQUIRED",result.status());
    assertTrue(result.review().findings().stream().anyMatch(f->f.path().equals("New.txt") && f.kind().equals("CONFLICT_MARKER")));
    assertArrayEquals(index,Files.readAllBytes(root.resolve(".git/index")));
  }
  @Test void binarySecretsOversizedFilesAndSubdirectoryRootsFailClosedBeforeTests() throws Exception {
    Files.write(root.resolve("New.bin"),new byte[]{0,1});assertEquals("BLOCKED",verify().status());Files.delete(root.resolve("New.bin"));
    Files.writeString(root.resolve(".env"),"token=private\n");assertEquals("BLOCKED",verify().status());Files.delete(root.resolve(".env"));
    Files.writeString(root.resolve("Large.txt"),"x".repeat(270000));assertEquals("BLOCKED",verify().status());Files.delete(root.resolve("Large.txt"));
    Path sub=Files.createDirectory(root.resolve("nested"));assertEquals("BLOCKED",service().verify(sub,new SelfPatchReviewService.Request("fixture",10)).status());
    assertEquals(0,tests.get());
  }
  @Test void stagingAndDeletionRefreshTheFingerprint() throws Exception {
    Files.writeString(root.resolve("A.txt"),"after\n");var inspector=new GitPatchInspector(new ExternalAgentProcessRunner());long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
    var first=inspector.capture(root,deadline);git("add","--","A.txt");var staged=inspector.capture(root,deadline);assertNotEquals(first.version(),staged.version());
    Files.delete(root.resolve("A.txt"));var deleted=inspector.capture(root,deadline);assertTrue(deleted.complete());assertNotEquals(staged.version(),deleted.version());
  }
  @Test void repositoryExternalDiffAndFsmonitorConfigurationAreNotExecuted() throws Exception {
    git("config","diff.external","definitely-missing-diff-helper");git("config","core.fsmonitor","definitely-missing-fsmonitor-helper");
    Files.writeString(root.resolve("A.txt"),"after\n");assertEquals("VERIFIED_CHECKS",verify().status());
  }
  @Test void noPatchAndTooManyFilesDoNotStartTests() throws Exception {
    assertEquals("NO_PATCH",verify().status());
    for(int i=0;i<129;i++)Files.writeString(root.resolve("New"+i+".txt"),"new\n");
    assertEquals("BLOCKED",verify().status());assertEquals(0,tests.get());
  }
  @Test void successfulExitWithTruncatedRealLogsCannotVerify() throws Exception {
    Files.writeString(root.resolve("A.txt"),"after\n");
    String command=System.getProperty("os.name").toLowerCase().contains("win")?"Write-Output ('x' * 70000); exit 0":"printf '%70000s' ''";
    var result=new SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService()).verify(root,new SelfPatchReviewService.Request(command,10));
    assertEquals("INITIAL_TEST_FAILED",result.status());assertTrue(result.initialTest().logsTruncated());assertNull(result.finalTest());
  }
  @Test void realTestTimeoutStopsTheCycleBeforeReviewAndFinalTest() throws Exception {
    Files.writeString(root.resolve("A.txt"),"after\n");
    String command=System.getProperty("os.name").toLowerCase().contains("win")?"Start-Sleep -Seconds 3; exit 0":"sleep 3";
    var result=new SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService()).verify(root,new SelfPatchReviewService.Request(command,1));
    assertEquals("INITIAL_TEST_FAILED",result.status());assertTrue(result.initialTest().timedOut());assertNull(result.review());assertNull(result.finalTest());
  }
}
