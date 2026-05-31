package dev.mikoto2000.rei.memory.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.MemoryConsolidatorService;
import dev.mikoto2000.rei.memory.service.MemoryConflictResolver;
import dev.mikoto2000.rei.memory.service.MemoryExporter;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import picocli.CommandLine;

class MemoryCommandTest {

  @Test
  void summarizeDoesNotSaveWithoutSaveOption() {
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
  void consolidateDoesNotSaveWithoutSaveOption() {
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
  void exportPrintsNoTargetMessageWhenCountZero() {
    MemoryExporter exporter = Mockito.mock(MemoryExporter.class);
    when(exporter.export(any())).thenReturn(new MemoryExporter.ExportResult(null, null, null, 0));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new MemoryCommand.ExportCommand(exporter)).execute();
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("エクスポート対象の記憶がありません"));
  }

  @Test
  void forgetShowsNotFoundMessage() {
    MemoryService service = Mockito.mock(MemoryService.class);
    when(service.findById("missing")).thenReturn(Optional.empty());

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new MemoryCommand.ForgetCommand(service)).execute("missing");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }
    assertTrue(out.toString().contains("指定された ID の記憶が見つかりません"));
  }
}
