package dev.mikoto2000.rei.conversation;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.storage.*;

class ConversationAdmissionStoreTest {
  @TempDir Path root;
  final Clock clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"),ZoneOffset.UTC);
  SessionMetadata session(String id) { return new SessionMetadata(id,"project","title",clock.instant(),clock.instant()); }
  AgentRunContext run(String id,String session) { return new AgentRunContext(id,session,root,"project",AgentRunContext.RequestSource.WEB); }
  @Test void acceptancePersistsSessionRunAndReceiptTogetherAndReplaysAfterRestart() throws Exception {
    try (var gate = new StorageMigrationCoordinator(root)) {
      gate.prepare();
      try (var store = new ConversationAdmissionStore(root,clock)) {
        var first = store.accept(session("s"),run("r","s"),"key","a".repeat(64));
        assertFalse(first.replay());
        var duplicate = store.accept(session("other"),run("other","other"),"key","a".repeat(64));
        assertTrue(duplicate.replay());
        assertEquals(first.context(),duplicate.context());
        assertEquals("r",store.lookup("key").orElseThrow().context().runId());
        assertTrue(new SqliteSessionRepository(root).findById("s").isPresent());
        assertTrue(new SqliteSessionRepository(root).findById("other").isEmpty());
      }
      try (var restored = new ConversationAdmissionStore(root,clock)) {
        assertEquals(RunStatus.UNKNOWN,restored.lookup("key").orElseThrow().status());
        assertTrue(restored.accept(session("other"),run("other","other"),"key","a".repeat(64)).replay());
      }
    }
  }
  @Test void changedRequestCannotReuseAKeyAndRejectedSessionIsNotPersisted() throws Exception {
    try (var gate = new StorageMigrationCoordinator(root)) {
      gate.prepare();
      try (var store = new ConversationAdmissionStore(root,clock)) {
        store.accept(session("s"),run("r","s"),"key","a".repeat(64));
        assertThrows(IdempotencyConflictException.class,()->store.accept(session("other"),run("other","other"),"key","b".repeat(64)));
        assertTrue(new SqliteSessionRepository(root).findById("other").isEmpty());
      }
    }
  }
  @Test void sessionsAreExclusiveUntilActualRunnerCleanupEvenAfterCancellation() throws Exception {
    try (var gate = new StorageMigrationCoordinator(root)) {
      gate.prepare();
      try (var store = new ConversationAdmissionStore(root,clock)) {
        store.accept(session("s"),run("r","s"),null,null);
        store.accept(session("other"),run("other","other"),null,null);
        var conflict = assertThrows(SessionBusyException.class,()->store.accept(session("s"),run("next","s"),null,null));
        assertEquals("r",conflict.runId());
        store.transition("r",RunStatus.CANCELLED);
        assertThrows(SessionBusyException.class,()->store.accept(session("s"),run("next","s"),null,null));
        store.release("r");
        assertFalse(store.accept(session("s"),run("next","s"),null,null).replay());
      }
    }
  }
  @Test void expiredKeysNeverAutomaticallyCreateAnotherRun() throws Exception {
    try (var gate = new StorageMigrationCoordinator(root)) {
      gate.prepare();
      try (var store = new ConversationAdmissionStore(root,clock)) {
        store.accept(session("s"),run("r","s"),"key","a".repeat(64));
      }
      var later = Clock.fixed(clock.instant().plus(Duration.ofDays(8)),ZoneOffset.UTC);
      try (var store = new ConversationAdmissionStore(root,later)) {
        assertThrows(IdempotencyExpiredException.class,()->store.lookup("key"));
        assertThrows(IdempotencyExpiredException.class,()->store.accept(session("s"),run("next","s"),"key","a".repeat(64)));
        assertTrue(store.get("next").isEmpty());
      }
    }
  }
  @Test void concurrentReceiptAndSessionAdmissionProduceOnlyOneRun() throws Exception {
    try (var gate = new StorageMigrationCoordinator(root)) {
      gate.prepare();
      try (var store = new ConversationAdmissionStore(root,clock);
           var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var tasks = java.util.stream.IntStream.range(0,2).mapToObj(i -> executor.submit(()->{
          barrier.await();return store.accept(session("s"),run("r"+i,"s"),"key","a".repeat(64));
        })).toList();
        var a=tasks.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS);
        var b=tasks.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(a.context(),b.context());assertNotEquals(a.replay(),b.replay());
      }
    }
  }
  @Test void failedMetadataValidationRollsBackRunLeaseAndReceipt()throws Exception {
    try(var gate=new StorageMigrationCoordinator(root)) {
      gate.prepare();
      try(var store=new ConversationAdmissionStore(root,clock)) {
        store.accept(session("s"),run("r","s"),null,null);store.release("r");
        var changed=new SessionMetadata("s","project","changed title",clock.instant(),clock.instant());
        assertThrows(IllegalArgumentException.class,()->store.accept(changed,run("next","s"),"bad","a".repeat(64)));
        assertTrue(store.get("next").isEmpty());assertTrue(store.lookup("bad").isEmpty());
        store.accept(session("s"),run("good","s"),"good","a".repeat(64));
      }
    }
  }
  @Test void anotherStoreCannotReleaseAStillLiveOwnersSession()throws Exception {
    try(var gate=new StorageMigrationCoordinator(root)) {
      gate.prepare();
      try(var first=new ConversationAdmissionStore(root,clock);var other=new ConversationAdmissionStore(root,clock)) {
        first.accept(session("s"),run("r","s"),null,null);
        other.release("r");assertEquals(RunStatus.QUEUED,first.get("r").orElseThrow().status());
        assertThrows(SessionBusyException.class,()->other.accept(session("s"),run("next","s"),null,null));
      }
    }
  }
  @Test void legacyShellRetainsItsExclusiveInputQueueButOnlyOneRunCanExecute()throws Exception {
    try(var gate=new StorageMigrationCoordinator(root)) {
      gate.prepare();
      try(var store=new ConversationAdmissionStore(root,clock)) {
        var first=new AgentRunContext("first","s",root,"project",AgentRunContext.RequestSource.SHELL);
        var pending=new AgentRunContext("pending","s",root,"project",AgentRunContext.RequestSource.SHELL);
        store.accept(session("s"),first,null,null);store.accept(session("s"),pending,null,null);
        assertThrows(SessionBusyException.class,()->store.startExecution(pending));
        store.transition("first",RunStatus.COMPLETED);store.release("first");
        assertThrows(SessionBusyException.class,()->store.accept(session("s"),run("web","s"),null,null));
        store.startExecution(pending);store.transition("pending",RunStatus.RUNNING);
        assertEquals(RunStatus.RUNNING,store.get("pending").orElseThrow().status());
      }
    }
  }
}
