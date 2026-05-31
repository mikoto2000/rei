package dev.mikoto2000.rei.memory.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.MemoryConsolidatorService;
import dev.mikoto2000.rei.memory.service.MemoryConflictResolver;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import picocli.CommandLine;

class MemoryConsolidateCommandTest {

  @Test
  void consolidateDoesNotSaveWithoutApproveOption() {
    MemoryConsolidatorService consolidator = Mockito.mock(MemoryConsolidatorService.class);
    MemoryService memoryService = Mockito.mock(MemoryService.class);
    SensitiveInfoDetector detector = Mockito.mock(SensitiveInfoDetector.class);
    MemoryConflictResolver resolver = Mockito.mock(MemoryConflictResolver.class);

    Memory candidate = new Memory("1", "candidate", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM,
        MemoryStatus.CANDIDATE, 0.8d, null, null, null);
    when(consolidator.extractCandidates()).thenReturn(List.of(candidate));
    when(memoryService.listActiveWithExpiryCheck()).thenReturn(List.of());
    when(detector.containsSensitiveInfo(any())).thenReturn(false);
    when(resolver.check(any(), any())).thenReturn(new MemoryConflictResolver.ConflictResult(
        MemoryConflictResolver.ConflictType.NONE, null, 0.2d));

    var cmd = new CommandLine(new MemoryCommand.ConsolidateCommand(consolidator, memoryService, detector, resolver));
    int exitCode = cmd.execute();

    assertEquals(0, exitCode);
    verify(memoryService, never()).save(any());
  }

  @Test
  void consolidateSavesWhenApproved() {
    MemoryConsolidatorService consolidator = Mockito.mock(MemoryConsolidatorService.class);
    MemoryService memoryService = Mockito.mock(MemoryService.class);
    SensitiveInfoDetector detector = Mockito.mock(SensitiveInfoDetector.class);
    MemoryConflictResolver resolver = Mockito.mock(MemoryConflictResolver.class);

    Memory candidate = new Memory("1", "candidate", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM,
        MemoryStatus.CANDIDATE, 0.8d, null, null, null);
    when(consolidator.extractCandidates()).thenReturn(List.of(candidate));
    when(memoryService.listActiveWithExpiryCheck()).thenReturn(List.of());
    when(detector.containsSensitiveInfo(any())).thenReturn(false);
    when(resolver.check(any(), any())).thenReturn(new MemoryConflictResolver.ConflictResult(
        MemoryConflictResolver.ConflictType.NONE, null, 0.2d));

    var cmd = new CommandLine(new MemoryCommand.ConsolidateCommand(consolidator, memoryService, detector, resolver));
    int exitCode = cmd.execute("--approve");

    assertEquals(0, exitCode);
    verify(memoryService, times(1)).save(any());
  }
}
