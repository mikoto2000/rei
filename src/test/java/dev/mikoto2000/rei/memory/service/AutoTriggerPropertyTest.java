package dev.mikoto2000.rei.memory.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.mockito.Mockito;

import dev.mikoto2000.rei.memory.command.MemoryCommand;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import net.jqwik.api.Property;
import net.jqwik.api.ForAll;
import picocli.CommandLine;

class AutoTriggerPropertyTest {

  // Feature: ai-memory-consolidation, Property 14: 自動トリガー時の非保存
  @Property(tries = 20)
  void consolidateWithoutApproveNeverSaves(@ForAll boolean ignored) {
    MemoryConsolidatorService consolidator = Mockito.mock(MemoryConsolidatorService.class);
    MemoryService memoryService = Mockito.mock(MemoryService.class);
    SensitiveInfoDetector detector = Mockito.mock(SensitiveInfoDetector.class);
    MemoryConflictResolver resolver = Mockito.mock(MemoryConflictResolver.class);

    when(consolidator.extractCandidates()).thenReturn(List.of(
        new Memory("1", "candidate", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM, MemoryStatus.CANDIDATE,
            0.8d, null, null, null)));
    when(memoryService.listActiveWithExpiryCheck()).thenReturn(List.of());
    when(detector.containsSensitiveInfo(any())).thenReturn(false);
    when(resolver.check(any(), any())).thenReturn(new MemoryConflictResolver.ConflictResult(
        MemoryConflictResolver.ConflictType.NONE, null, 0.2d));

    new CommandLine(new MemoryCommand.ConsolidateCommand(consolidator, memoryService, detector, resolver)).execute();

    verify(memoryService, never()).save(any());
  }
}
