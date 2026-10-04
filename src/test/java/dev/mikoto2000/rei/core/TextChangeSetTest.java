package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TextChangeSetTest {
  @TempDir Path root;
  final String projectId=UUID.randomUUID().toString();
  final Instant at=Instant.parse("2026-10-05T10:00:00Z");
  ProjectContext project(){return new ProjectContext(projectId,"rei",root);}
  TextChangeSetRepository repository(){return new TextChangeSetRepository(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("changes.db")));}
  TextChangeSetService service(TextChangeSetRepository repository){return new TextChangeSetService(repository,Clock.fixed(at,ZoneOffset.UTC),()->UUID.randomUUID().toString());}
  TextChangeSetService.Writer writer(){return (path,oldText,newText)->Files.writeString(path,newText);}
  @Test void previewPersistsDiffWithoutWritingAndExplicitApplyUsesSavedProposal() throws Exception {
    var file=root.resolve("diagram.mmd");Files.writeString(file,"graph TD\n  A-->B\n");
    var service=service(repository());
    var proposal=service.propose(project(),new TextChangeSetService.Request("diagram.mmd","graph TD\n  A-->B\n","graph TD\n  A-->C\n"));
    assertEquals("PROPOSED",proposal.status());assertTrue(proposal.diff().contains("-  A-->B"));assertTrue(proposal.diff().contains("+  A-->C"));
    assertEquals("graph TD\n  A-->B\n",Files.readString(file));
    var restarted=service(repository());assertEquals(proposal,restarted.inspect(project(),proposal.id()));
    assertThrows(IllegalArgumentException.class,()->restarted.apply(project(),proposal.id(),"wrong",writer()));
    var receipt=restarted.apply(project(),proposal.id(),proposal.proposalSha256(),writer());
    assertEquals("APPLIED",receipt.status());assertEquals(receipt.proposedSha256(),receipt.currentSha256());
    assertEquals("graph TD\n  A-->C\n",Files.readString(file));
    assertEquals("APPLIED",restarted.apply(project(),proposal.id(),proposal.proposalSha256(),(p,o,n)->fail("no replay")).status());
  }
  @Test void refusesStaleContentAndForeignOwnerBeforeWriter() throws Exception {
    Files.writeString(root.resolve("note.md"),"old\n");var service=service(repository());
    assertThrows(IllegalArgumentException.class,()->service.propose(project(),new TextChangeSetService.Request("note.md","other","new")));
    var proposal=service.propose(project(),new TextChangeSetService.Request("note.md","old\n","new\n"));
    Files.writeString(root.resolve("note.md"),"human edit\n");
    assertEquals("STALE",service.apply(project(),proposal.id(),proposal.proposalSha256(),(p,o,n)->fail("no stale write")).status());
    assertEquals("human edit\n",Files.readString(root.resolve("note.md")));
    var other=new ProjectContext(UUID.randomUUID().toString(),"other",root);
    assertThrows(IllegalArgumentException.class,()->service.inspect(other,proposal.id()));
    assertThrows(IllegalArgumentException.class,()->service.apply(other,proposal.id(),proposal.proposalSha256(),writer()));
  }
  @Test void failedOrInterruptedApplyIsNotReplayedAfterRestart() throws Exception {
    Files.writeString(root.resolve("note.md"),"old");var service=service(repository());
    var proposal=service.propose(project(),new TextChangeSetService.Request("note.md","old","new"));
    assertThrows(java.io.IOException.class,()->service.apply(project(),proposal.id(),proposal.proposalSha256(),(p,o,n)->{Files.writeString(p,"partial");throw new java.io.IOException("interrupted");}));
    var restarted=service(repository());assertEquals("FAILED_UNCERTAIN",restarted.inspect(project(),proposal.id()).status());
    assertThrows(IllegalStateException.class,()->restarted.apply(project(),proposal.id(),proposal.proposalSha256(),(p,o,n)->fail("no uncertain replay")));
    assertEquals("partial",Files.readString(root.resolve("note.md")));
  }
  @Test void boundsTextAndRejectsSensitiveOutsideOrBinaryFiles() throws Exception {
    var service=service(repository());Files.writeString(root.resolve(".env"),"secret");
    assertThrows(IllegalArgumentException.class,()->service.propose(project(),new TextChangeSetService.Request(".env","secret","new")));
    assertThrows(IllegalArgumentException.class,()->service.propose(project(),new TextChangeSetService.Request("../outside","old","new")));
    Files.write(root.resolve("binary.txt"),new byte[]{0,1,2});
    assertThrows(IllegalArgumentException.class,()->service.propose(project(),new TextChangeSetService.Request("binary.txt","old","new")));
    Files.writeString(root.resolve("note.md"),"old");
    assertThrows(IllegalArgumentException.class,()->service.propose(project(),new TextChangeSetService.Request("note.md","old","x".repeat(65537))));
  }
  @Test void discardAndSavedApplyingStateNeverInvokeWriterAndMissingFileStillAllowsInspection() throws Exception {
    Files.writeString(root.resolve("note.md"),"old");var repository=repository();var service=service(repository);
    var proposal=service.propose(project(),new TextChangeSetService.Request("note.md","old","new"));
    assertEquals("DISCARDED",service.discard(project(),proposal.id(),proposal.proposalSha256()).status());
    assertThrows(IllegalStateException.class,()->service.apply(project(),proposal.id(),proposal.proposalSha256(),(p,o,n)->fail("no discarded write")));
    var pending=service.propose(project(),new TextChangeSetService.Request("note.md","old","other"));
    assertTrue(repository.transition(projectId,pending.id(),"PROPOSED","APPLYING"));
    var restarted=service(repository());assertEquals("APPLYING",restarted.inspect(project(),pending.id()).status());
    assertThrows(IllegalStateException.class,()->restarted.apply(project(),pending.id(),pending.proposalSha256(),(p,o,n)->fail("no startup replay")));
    Files.delete(root.resolve("note.md"));
    assertNull(restarted.inspect(project(),pending.id()).currentSha256());
    assertEquals("APPLYING",restarted.inspect(project(),pending.id()).status());
  }
  @Test void competingServiceCannotClaimProposalDuringWriteAndRootChangesAreRejected() throws Exception {
    Files.writeString(root.resolve("note.md"),"old");var service=service(repository());var second=service(repository());
    var proposal=service.propose(project(),new TextChangeSetService.Request("note.md","old","new"));
    var receipt=service.apply(project(),proposal.id(),proposal.proposalSha256(),(path,oldText,newText)->{
      assertThrows(IllegalStateException.class,()->second.apply(project(),proposal.id(),proposal.proposalSha256(),(p,o,n)->fail("no concurrent write")));
      Files.writeString(path,newText);
    });
    assertEquals("APPLIED",receipt.status());
    var relocated=new ProjectContext(projectId,"rei",Files.createDirectory(root.resolve("relocated")));
    assertThrows(IllegalArgumentException.class,()->second.inspect(relocated,proposal.id()));
  }
  @Test void baselineReadPreservesBomCrLfAndMissingFinalNewlineWithoutSavingProposal() throws Exception {
    var text="\ufefftitle\r\nbody";Files.writeString(root.resolve("note.md"),text);var service=service(repository());
    var baseline=service.readBase(project(),"note.md");assertEquals(text,baseline.text());assertEquals("note.md",baseline.path());
    var proposal=service.propose(project(),new TextChangeSetService.Request(baseline.path(),baseline.text(),"\ufefftitle\r\nnew body"));
    assertEquals(baseline.sha256(),proposal.baselineSha256());
    assertTrue(proposal.diff().contains("\\ No newline at end of file"));
  }
}
