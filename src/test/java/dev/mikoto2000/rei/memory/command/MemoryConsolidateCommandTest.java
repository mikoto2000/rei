package dev.mikoto2000.rei.memory.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.MemoryConflictResolver;
import dev.mikoto2000.rei.memory.service.MemoryConsolidatorService;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import picocli.CommandLine;

/** 記憶Consolidateコマンドの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class MemoryConsolidateCommandTest {
  @Test
  void rejectedSecretNeverAppearsOnEitherOutputWithOrWithoutApproval() {
    for (boolean approved : List.of(false, true)) {
      var consolidator = Mockito.mock(MemoryConsolidatorService.class);
      var memoryService = Mockito.mock(MemoryService.class);
      var resolver = Mockito.mock(MemoryConflictResolver.class);
      String secret = "ghp_" + "a".repeat(36);
      var candidate =
          new Memory(
              "1",
              secret,
              MemoryType.KNOWLEDGE,
              MemoryScope.SHORT_TERM,
              MemoryStatus.CANDIDATE,
              0.8d,
              null,
              null,
              null);
      when(consolidator.extractCandidates()).thenReturn(List.of(candidate));
      when(memoryService.listActiveWithExpiryCheck()).thenReturn(List.of());
      var command =
          new CommandLine(
              new MemoryCommand.ConsolidateCommand(
                  consolidator, memoryService, new SensitiveInfoDetector(), resolver));
      var output = new java.io.ByteArrayOutputStream();
      var error = new java.io.ByteArrayOutputStream();
      var previousOut = System.out;
      var previousErr = System.err;
      try {
        System.setOut(
            new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(
            new java.io.PrintStream(error, true, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, command.execute(approved ? new String[] {"--approve"} : new String[0]));
      } finally {
        System.setOut(previousOut);
        System.setErr(previousErr);
      }
      org.junit.jupiter.api.Assertions.assertFalse(
          (output.toString(java.nio.charset.StandardCharsets.UTF_8)
                  + error.toString(java.nio.charset.StandardCharsets.UTF_8))
              .contains(secret));
      org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("SECRET_KEY"));
      verify(memoryService, never()).save(any());
      verify(resolver, never()).check(any(), any());
    }
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void consolidateDoesNotSaveWithoutApproveOption() {
    MemoryConsolidatorService consolidator = Mockito.mock(MemoryConsolidatorService.class);
    MemoryService memoryService = Mockito.mock(MemoryService.class);
    SensitiveInfoDetector detector = Mockito.mock(SensitiveInfoDetector.class);
    MemoryConflictResolver resolver = Mockito.mock(MemoryConflictResolver.class);

    Memory candidate =
        new Memory(
            "1",
            "candidate",
            MemoryType.KNOWLEDGE,
            MemoryScope.SHORT_TERM,
            MemoryStatus.CANDIDATE,
            0.8d,
            null,
            null,
            null);
    when(consolidator.extractCandidates()).thenReturn(List.of(candidate));
    when(memoryService.listActiveWithExpiryCheck()).thenReturn(List.of());
    when(detector.containsSensitiveInfo(any())).thenReturn(false);
    when(resolver.check(any(), any()))
        .thenReturn(
            new MemoryConflictResolver.ConflictResult(
                MemoryConflictResolver.ConflictType.NONE, null, 0.2d));

    var cmd =
        new CommandLine(
            new MemoryCommand.ConsolidateCommand(consolidator, memoryService, detector, resolver));
    int exitCode = cmd.execute();

    assertEquals(0, exitCode);
    verify(memoryService, never()).save(any());
  }

  /** 長期記憶の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void consolidateSavesWhenApproved() {
    MemoryConsolidatorService consolidator = Mockito.mock(MemoryConsolidatorService.class);
    MemoryService memoryService = Mockito.mock(MemoryService.class);
    SensitiveInfoDetector detector = Mockito.mock(SensitiveInfoDetector.class);
    MemoryConflictResolver resolver = Mockito.mock(MemoryConflictResolver.class);

    Memory candidate =
        new Memory(
            "1",
            "candidate",
            MemoryType.KNOWLEDGE,
            MemoryScope.SHORT_TERM,
            MemoryStatus.CANDIDATE,
            0.8d,
            null,
            null,
            null);
    when(consolidator.extractCandidates()).thenReturn(List.of(candidate));
    when(memoryService.listActiveWithExpiryCheck()).thenReturn(List.of());
    when(detector.containsSensitiveInfo(any())).thenReturn(false);
    when(resolver.check(any(), any()))
        .thenReturn(
            new MemoryConflictResolver.ConflictResult(
                MemoryConflictResolver.ConflictType.NONE, null, 0.2d));

    var cmd =
        new CommandLine(
            new MemoryCommand.ConsolidateCommand(consolidator, memoryService, detector, resolver));
    int exitCode = cmd.execute("--approve");

    assertEquals(0, exitCode);
    verify(memoryService, times(1)).save(any());
  }

  /** 長期記憶の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void consolidateSkipsWhenConflictCheckTimesOut() {
    MemoryConsolidatorService consolidator = Mockito.mock(MemoryConsolidatorService.class);
    MemoryService memoryService = Mockito.mock(MemoryService.class);
    SensitiveInfoDetector detector = Mockito.mock(SensitiveInfoDetector.class);
    MemoryConflictResolver resolver = Mockito.mock(MemoryConflictResolver.class);

    Memory candidate =
        new Memory(
            "1",
            "candidate",
            MemoryType.KNOWLEDGE,
            MemoryScope.SHORT_TERM,
            MemoryStatus.CANDIDATE,
            0.8d,
            null,
            null,
            null);
    when(consolidator.extractCandidates()).thenReturn(List.of(candidate));
    when(consolidator.conflictTimeoutSeconds()).thenReturn(1);
    when(memoryService.listActiveWithExpiryCheck()).thenReturn(List.of());
    when(detector.containsSensitiveInfo(any())).thenReturn(false);
    when(resolver.check(any(), any()))
        .thenAnswer(invocation -> {
          Thread.sleep(1_200);
          return new MemoryConflictResolver.ConflictResult(
              MemoryConflictResolver.ConflictType.NONE, null, 0.2d);
        });

    var cmd =
        new CommandLine(
            new MemoryCommand.ConsolidateCommand(consolidator, memoryService, detector, resolver));
    int exitCode = cmd.execute("--approve");

    assertEquals(0, exitCode);
    verify(memoryService, never()).save(any());
  }
}
