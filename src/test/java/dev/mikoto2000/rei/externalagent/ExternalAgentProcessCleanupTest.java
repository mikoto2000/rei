package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** No real process is signalled: adversarial process snapshots use mocks. */
class ExternalAgentProcessCleanupTest {
  Process process(boolean alive, ProcessHandle... descendants) throws Exception {
    var process = mock(Process.class); var info = mock(ProcessHandle.Info.class);
    when(process.isAlive()).thenReturn(alive); when(process.info()).thenReturn(info);
    when(info.startInstant()).thenReturn(Optional.of(Instant.parse("2026-01-01T00:00:00Z")));
    when(process.descendants()).thenAnswer(invocation -> Stream.of(descendants));
    when(process.waitFor(1, TimeUnit.SECONDS)).thenReturn(true); return process;
  }
  ProcessHandle child(long pid, Instant start) {
    var child = mock(ProcessHandle.class); var info = mock(ProcessHandle.Info.class);
    when(child.pid()).thenReturn(pid); when(child.isAlive()).thenReturn(true); when(child.info()).thenReturn(info);
    when(info.startInstant()).thenReturn(Optional.ofNullable(start));
    when(child.onExit()).thenReturn(CompletableFuture.completedFuture(child)); return child;
  }
  void cleanup(Process process, Set<ProcessHandle> known) throws Exception {
    var method = ExternalAgentProcessRunner.class.getDeclaredMethod("cleanup", Process.class, Set.class);
    method.setAccessible(true); method.invoke(null, process, known);
  }
  @Test void exitedRootIsNeverEnumeratedAgain() throws Exception {
    var child = child(999999, Instant.parse("2026-01-02T00:00:00Z")); var process = process(false, child);
    cleanup(process, new LinkedHashSet<>());
    verify(process, never()).descendants(); verify(child, never()).destroyForcibly();
  }
  @Test void currentJvmAndItsAncestorsAreNeverSignalledEvenIfAlreadyRetained() throws Exception {
    var protectedIds = new ArrayList<Long>(); var current = ProcessHandle.current();
    protectedIds.add(current.pid()); for (var parent = current.parent(); parent.isPresent(); parent = parent.get().parent()) protectedIds.add(parent.get().pid());
    for (long pid : protectedIds) {
      var child = child(pid, Instant.parse("2026-01-02T00:00:00Z"));
      cleanup(process(false), new LinkedHashSet<>(List.of(child)));
      verify(child, never()).destroyForcibly(); verify(child, never()).onExit();
    }
  }
  @Test void olderOrUnknownStartTimeCannotEstablishDescendantOwnership() throws Exception {
    var older = child(999998, Instant.parse("2025-01-01T00:00:00Z")); var unknown = child(999999, null);
    cleanup(process(true, older, unknown), new LinkedHashSet<>());
    verify(older, never()).destroyForcibly(); verify(unknown, never()).destroyForcibly();
  }
  @Test void rootExitDuringEnumerationDiscardsTheSnapshot() throws Exception {
    var child = child(999999, Instant.parse("2026-01-02T00:00:00Z")); var process = process(true, child);
    when(process.isAlive()).thenReturn(true, false);
    cleanup(process, new LinkedHashSet<>());
    verify(child, never()).destroyForcibly();
  }
  @Test void verifiedLiveDescendantIsStillCleanedUp() throws Exception {
    var child = child(999999, Instant.parse("2026-01-02T00:00:00Z")); var process = process(true, child);
    cleanup(process, new LinkedHashSet<>());
    verify(child).destroyForcibly(); verify(process).destroyForcibly();
  }
  @Test void previouslyVerifiedHandleSurvivesRootExit() throws Exception {
    var child = child(999999, Instant.parse("2026-01-02T00:00:00Z"));
    cleanup(process(false), new LinkedHashSet<>(List.of(child)));
    verify(child).destroyForcibly();
  }
  @Test void changedRootStartTimeDiscardsSnapshot() throws Exception {
    var child = child(999999, Instant.parse("2026-01-02T00:00:00Z")); var process = process(true, child);
    when(process.info().startInstant()).thenReturn(Optional.of(Instant.parse("2026-01-01T00:00:00Z")),
        Optional.of(Instant.parse("2026-01-03T00:00:00Z")));
    cleanup(process, new LinkedHashSet<>());
    verify(child, never()).destroyForcibly();
  }
}
