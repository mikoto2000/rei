package dev.mikoto2000.rei.externalagent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 外部エージェントプロセス後片付けの結果と状態の契約を検証するテスト。
 *
 * <p>No real process is signalled: adversarial process snapshots use mocks.
 */
class ExternalAgentProcessCleanupTest {
  /** {@code process} の条件を再現するための補助情報を返す。 */
  Process process(boolean alive, ProcessHandle... descendants) throws Exception {
    var process = mock(Process.class);
    var info = mock(ProcessHandle.Info.class);
    when(process.isAlive()).thenReturn(alive);
    when(process.info()).thenReturn(info);
    when(info.startInstant()).thenReturn(Optional.of(Instant.parse("2026-01-01T00:00:00Z")));
    when(process.descendants()).thenAnswer(invocation -> Stream.of(descendants));
    when(process.waitFor(1, TimeUnit.SECONDS)).thenReturn(true);
    return process;
  }

  /** {@code child} の条件を再現するための補助情報を返す。 */
  ProcessHandle child(long pid, Instant start) {
    var child = mock(ProcessHandle.class);
    var info = mock(ProcessHandle.Info.class);
    when(child.pid()).thenReturn(pid);
    when(child.isAlive()).thenReturn(true);
    when(child.info()).thenReturn(info);
    when(info.startInstant()).thenReturn(Optional.ofNullable(start));
    when(child.onExit()).thenReturn(CompletableFuture.completedFuture(child));
    return child;
  }

  /** 検証で使用した資源を解放し、後続の検証に影響を残さないようにする。 */
  void cleanup(Process process, Set<ProcessHandle> known) throws Exception {
    var method =
        ExternalAgentProcessRunner.class.getDeclaredMethod("cleanup", Process.class, Set.class);
    method.setAccessible(true);
    method.invoke(null, process, known);
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void exitedRootIsNeverEnumeratedAgain() throws Exception {
    var child = child(999999, Instant.parse("2026-01-02T00:00:00Z"));
    var process = process(false, child);
    cleanup(process, new LinkedHashSet<>());
    verify(process, never()).descendants();
    verify(child, never()).destroyForcibly();
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void currentJvmAndItsAncestorsAreNeverSignalledEvenIfAlreadyRetained() throws Exception {
    var protectedIds = new ArrayList<Long>();
    var current = ProcessHandle.current();
    protectedIds.add(current.pid());
    for (var parent = current.parent(); parent.isPresent(); parent = parent.get().parent())
      protectedIds.add(parent.get().pid());
    for (long pid : protectedIds) {
      var child = child(pid, Instant.parse("2026-01-02T00:00:00Z"));
      cleanup(process(false), new LinkedHashSet<>(List.of(child)));
      verify(child, never()).destroyForcibly();
      verify(child, never()).onExit();
    }
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void olderOrUnknownStartTimeCannotEstablishDescendantOwnership() throws Exception {
    var older = child(999998, Instant.parse("2025-01-01T00:00:00Z"));
    var unknown = child(999999, null);
    cleanup(process(true, older, unknown), new LinkedHashSet<>());
    verify(older, never()).destroyForcibly();
    verify(unknown, never()).destroyForcibly();
  }

  /** 外部エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void rootExitDuringEnumerationDiscardsTheSnapshot() throws Exception {
    var child = child(999999, Instant.parse("2026-01-02T00:00:00Z"));
    var process = process(true, child);
    when(process.isAlive()).thenReturn(true, false);
    cleanup(process, new LinkedHashSet<>());
    verify(child, never()).destroyForcibly();
  }

  /** 外部エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void verifiedLiveDescendantIsStillCleanedUp() throws Exception {
    var child = child(999999, Instant.parse("2026-01-02T00:00:00Z"));
    var process = process(true, child);
    cleanup(process, new LinkedHashSet<>());
    verify(child).destroyForcibly();
    verify(process).destroyForcibly();
  }

  /** 作業ルート終了の後も対象の情報が残ることを確認する。 */
  @Test
  void previouslyVerifiedHandleSurvivesRootExit() throws Exception {
    var child = child(999999, Instant.parse("2026-01-02T00:00:00Z"));
    cleanup(process(false), new LinkedHashSet<>(List.of(child)));
    verify(child).destroyForcibly();
  }

  /** 外部エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void changedRootStartTimeDiscardsSnapshot() throws Exception {
    var child = child(999999, Instant.parse("2026-01-02T00:00:00Z"));
    var process = process(true, child);
    when(process.info().startInstant())
        .thenReturn(
            Optional.of(Instant.parse("2026-01-01T00:00:00Z")),
            Optional.of(Instant.parse("2026-01-03T00:00:00Z")));
    cleanup(process, new LinkedHashSet<>());
    verify(child, never()).destroyForcibly();
  }
}
