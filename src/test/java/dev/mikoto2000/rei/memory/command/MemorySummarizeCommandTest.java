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
import dev.mikoto2000.rei.memory.service.MemoryService;
import picocli.CommandLine;

class MemorySummarizeCommandTest {

  @Test
  void summarizeDoesNotSaveWithoutApproveOption() {
    MemoryConsolidatorService consolidator = Mockito.mock(MemoryConsolidatorService.class);
    MemoryService memoryService = Mockito.mock(MemoryService.class);
    when(consolidator.extractCandidates()).thenReturn(List.of(new Memory("1", "msg", MemoryType.KNOWLEDGE,
        MemoryScope.SHORT_TERM, MemoryStatus.CANDIDATE, 0.8d, null, null, null)));
    when(consolidator.summarize(any())).thenReturn("summary");

    var cmd = new CommandLine(new MemoryCommand.SummarizeCommand(consolidator, memoryService));
    int exitCode = cmd.execute();

    assertEquals(0, exitCode);
    verify(memoryService, never()).save(any());
  }

  @Test
  void summarizeSavesWhenApproved() {
    MemoryConsolidatorService consolidator = Mockito.mock(MemoryConsolidatorService.class);
    MemoryService memoryService = Mockito.mock(MemoryService.class);
    when(consolidator.extractCandidates()).thenReturn(List.of(new Memory("1", "msg", MemoryType.KNOWLEDGE,
        MemoryScope.SHORT_TERM, MemoryStatus.CANDIDATE, 0.8d, null, null, null)));
    when(consolidator.summarize(any())).thenReturn("summary");

    var cmd = new CommandLine(new MemoryCommand.SummarizeCommand(consolidator, memoryService));
    int exitCode = cmd.execute("--approve");

    assertEquals(0, exitCode);
    verify(memoryService, times(1)).save(any());
  }
}
