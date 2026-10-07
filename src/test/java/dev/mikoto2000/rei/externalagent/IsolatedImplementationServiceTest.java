package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import static org.junit.jupiter.api.Assertions.*;

class IsolatedImplementationServiceTest {
  @TempDir Path temporary;
  Path root;AgentRunContext owner;IsolatedImplementationService service;
  @BeforeEach void setup()throws Exception {
    root=Files.createDirectory(temporary.resolve("project"));git(root,"init","--quiet");git(root,"config","core.autocrlf","false");
    Files.writeString(root.resolve("A.txt"),"before\n");git(root,"add","--","A.txt");git(root,"commit","--quiet","--no-gpg-sign","-m","fixture");
    owner=new AgentRunContext("run","session",root,"project");
    service=new IsolatedImplementationService(temporary.resolve("private"),new ExternalAgentProcessRunner(),new SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService()));
  }
  String command(){return System.getProperty("os.name").startsWith("Windows")?"exit 0":"true";}
  IsolatedImplementationService.Receipt implement()throws Exception {
    return service.implement(owner,"A.txt",command(),10,()->false,(worktree,manifest)->new ImplementationProposal(List.of(new ImplementationProposal.Edit("A.txt",manifest.get("A.txt"),"after\n"))));
  }
  @Test void parentStaysUnchangedUntilExplicitVerifiedMergeAndReceiptSurvivesRestart()throws Exception {
    var receipt=implement();assertEquals("READY_FOR_APPROVAL",receipt.status(),receipt.toString());
    assertEquals("before\n",Files.readString(root.resolve("A.txt")));assertEquals(List.of("A.txt"),receipt.changedFiles());
    assertEquals("VERIFIED_CHECKS",receipt.verification().status());assertNotNull(receipt.commitHash());assertEquals(64,receipt.patchHash().length());
    var preview=service.preview(owner,receipt.id());assertEquals(receipt.patchHash(),preview.patchHash());assertTrue(preview.diff().contains("+after"));assertTrue(preview.diff().contains("-before"));
    var restarted=new IsolatedImplementationService(temporary.resolve("private"),new ExternalAgentProcessRunner(),new SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService()));
    assertEquals(receipt,restarted.get(owner,receipt.id()));
    assertThrows(IllegalArgumentException.class,()->restarted.merge(owner,receipt.id(),receipt.patchHash(),"ordinary review"));
    assertThrows(IllegalArgumentException.class,()->restarted.merge(owner,receipt.id(),"0".repeat(64),"/agent codex merge "+receipt.id()+" "+receipt.patchHash()));
    var merged=restarted.merge(owner,receipt.id(),receipt.patchHash(),"/agent codex merge "+receipt.id()+" "+receipt.patchHash());
    assertEquals("MERGED",merged.status());assertEquals("after\n",Files.readString(root.resolve("A.txt")));
    assertThrows(IllegalArgumentException.class,()->restarted.merge(owner,receipt.id(),receipt.patchHash(),"/agent codex merge "+receipt.id()+" "+receipt.patchHash()));
  }
  @Test void dirtyParentAndStaleParentRejectWithoutLosingEdits()throws Exception {
    Files.writeString(root.resolve("A.txt"),"human edit\n");
    assertThrows(IllegalArgumentException.class,this::implement);assertEquals("human edit\n",Files.readString(root.resolve("A.txt")));
    Files.writeString(root.resolve("A.txt"),"before\n");var receipt=implement();
    Files.writeString(root.resolve("A.txt"),"later human edit\n");
    assertThrows(IllegalArgumentException.class,()->service.merge(owner,receipt.id(),receipt.patchHash(),"/agent codex merge "+receipt.id()+" "+receipt.patchHash()));
    assertEquals("later human edit\n",Files.readString(root.resolve("A.txt")));
  }
  @Test void failedTestsUnexpectedFilesAndForeignOwnershipNeverAuthorizeMerge()throws Exception {
    var bad=service.implement(owner,"A.txt","exit 1",10,()->false,(tree,manifest)->new ImplementationProposal(List.of(new ImplementationProposal.Edit("A.txt",manifest.get("A.txt"),"after\n"))));
    assertEquals("FAILED",bad.status());assertNull(bad.commitHash());
    var unexpected=service.implement(owner,"A.txt",command(),10,()->false,(tree,manifest)->{Files.writeString(tree.resolve("unexpected.txt"),"outside proposal");return new ImplementationProposal(List.of(new ImplementationProposal.Edit("A.txt",manifest.get("A.txt"),"after\n")));});
    assertEquals("FAILED",unexpected.status());assertNull(unexpected.commitHash());
    var other=new AgentRunContext("other","otherSession",root,"project");assertThrows(IllegalArgumentException.class,()->service.get(other,bad.id()));
    assertEquals("before\n",Files.readString(root.resolve("A.txt")));
  }
  @Test void modifiedCommittedWorktreeRequiresReReviewBeforeMerge()throws Exception {
    var receipt=implement();Files.writeString(Path.of(receipt.worktree()).resolve("A.txt"),"unreviewed edit\n");
    assertThrows(IllegalArgumentException.class,()->service.merge(owner,receipt.id(),receipt.patchHash(),"/agent codex merge "+receipt.id()+" "+receipt.patchHash()));
    assertEquals("before\n",Files.readString(root.resolve("A.txt")));
  }
  @Test void aConcurrentConflictingCommitIsDetectedWithoutAbortingOrRetryingTheMerge()throws Exception {
    var receipt=implement();
    var racing=new ExternalAgentProcessRunner(){@Override public Output run(List<String> command,Path cwd,String input,java.time.Duration total,java.time.Duration idle,int bytes,java.util.function.BooleanSupplier cancelled){
      if(command.contains("merge") && command.contains("--no-ff"))try{Files.writeString(root.resolve("A.txt"),"concurrent edit\n");git(root,"add","A.txt");git(root,"commit","--quiet","--no-gpg-sign","-m","concurrent fixture");}catch(Exception error){throw new RuntimeException(error);}
      return super.run(command,cwd,input,total,idle,bytes,cancelled);
    }};
    var merger=new IsolatedImplementationService(temporary.resolve("private"),racing,new SelfPatchReviewService(new dev.mikoto2000.rei.core.service.SystemShellService()));
    var conflict=merger.merge(owner,receipt.id(),receipt.patchHash(),"/agent codex merge "+receipt.id()+" "+receipt.patchHash());assertEquals("CONFLICT",conflict.status());
    assertTrue(Files.exists(root.resolve(".git/MERGE_HEAD")));assertTrue(Files.readString(root.resolve("A.txt")).contains("<<<<<<<"));
    assertThrows(IllegalArgumentException.class,()->merger.merge(owner,receipt.id(),receipt.patchHash(),"/agent codex merge "+receipt.id()+" "+receipt.patchHash()));
  }
  void git(Path directory,String... args)throws Exception {
    var cmd=new ArrayList<String>(List.of("git","-c","user.name=Fixture","-c","user.email=fixture@example.invalid","-c","core.hooksPath="));cmd.addAll(List.of(args));
    var p=new ProcessBuilder(cmd).directory(directory.toFile()).redirectErrorStream(true).start();assertTrue(p.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,p.exitValue(),new String(p.getInputStream().readAllBytes()));
  }
}
