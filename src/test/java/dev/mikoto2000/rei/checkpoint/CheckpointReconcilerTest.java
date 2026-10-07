package dev.mikoto2000.rei.checkpoint;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class CheckpointReconcilerTest {
  @Test void readingGitBaselineDoesNotExecuteRepositoryConfiguredFsmonitor()throws Exception {
    Path repository=Files.createDirectory(root.resolve("git-fixture"));
    var init=new ProcessBuilder("git","init","--quiet",repository.toString()).start();assertEquals(0,init.waitFor());
    Path hook=repository.resolve(".git/fsmonitor-fixture.sh");Files.writeString(hook,"#!/bin/sh\nprintf called > fsmonitor-called.txt\nexit 0\n");hook.toFile().setExecutable(true);
    var config=new ProcessBuilder("git","-C",repository.toString(),"config","core.fsmonitor",hook.toString().replace('\\','/')).start();assertEquals(0,config.waitFor());
    CheckpointReconciler.git(repository);assertFalse(Files.exists(repository.resolve("fsmonitor-called.txt")));
  }
  @TempDir Path root;
  @Test void detectsChangesWithoutOverwritingAndNeverReplaysUnknownOperations() throws Exception {
    var reconciler=new CheckpointReconciler(null);
    var file=root.resolve("result.txt");Files.writeString(file,"before");
    var state=PersistentCheckpoint.initial("task","A","session","run",root,"request");
    state=new PersistentCheckpoint(1,1,state.createdAt(),state.taskId(),state.projectId(),state.projectRoot(),state.sessionId(),state.originalRunId(),state.runId(),null,null,state.request(),List.of(),List.of(),List.of(),null,"RUNNING",null,"test",List.of(),
        List.of(new PersistentCheckpoint.Operation("call","deploy",PersistentCheckpoint.OperationStatus.STARTED,"event")),Map.of(file.toString(),CheckpointReconciler.fingerprint(file)),Map.of(),List.of(),List.of(),null);
    Files.writeString(file,"after");var result=reconciler.check(state,false);
    assertEquals("after",Files.readString(file));
    assertFalse(result.changed().isEmpty());assertEquals(1,result.unknownOperations().size());
    assertEquals("CONFIRMATION_REQUIRED",result.decision());
    assertTrue(result.nextAction().contains("再確認"));
  }
  @Test void missingProjectBlocksResumeAndCancellationStopsInspection() {
    var state=PersistentCheckpoint.initial("task","A","session","run",root.resolve("missing"),"request");
    assertEquals("BLOCKED",new CheckpointReconciler(null).check(state,false).decision());
    Thread.currentThread().interrupt();
    try {assertThrows(java.util.concurrent.CancellationException.class,()->new CheckpointReconciler(null).check(state,false));}
    finally {Thread.interrupted();}
  }
}
