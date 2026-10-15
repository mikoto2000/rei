package dev.mikoto2000.rei.memory.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.memory.service.MemoryConsolidatorService;
import dev.mikoto2000.rei.memory.service.MemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import picocli.CommandLine;

class MemorySummarizeCommandTest {

  @Test
  void summarizeDoesNotSaveWithoutApproveOption() {
    MemoryConsolidatorService consolidator = Mockito.mock(MemoryConsolidatorService.class);
    MemoryService memoryService = Mockito.mock(MemoryService.class);
    when(consolidator.summarizeCandidates())
        .thenReturn(new MemoryConsolidatorService.Summary(true, "summary"));

    var cmd = new CommandLine(new MemoryCommand.SummarizeCommand(consolidator, memoryService));
    int exitCode = cmd.execute();

    assertEquals(0, exitCode);
    verify(memoryService, never()).save(any());
  }

  @Test
  void summarizeSavesWhenApproved() {
    MemoryConsolidatorService consolidator = Mockito.mock(MemoryConsolidatorService.class);
    MemoryService memoryService = Mockito.mock(MemoryService.class);
    when(consolidator.summarizeCandidates())
        .thenReturn(new MemoryConsolidatorService.Summary(true, "summary"));

    var cmd = new CommandLine(new MemoryCommand.SummarizeCommand(consolidator, memoryService));
    int exitCode = cmd.execute("--approve");

    assertEquals(0, exitCode);
    verify(memoryService, times(1)).save(any());
  }
}
