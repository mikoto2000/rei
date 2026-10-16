package dev.mikoto2000.rei.memory.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.memory.service.MemoryConflictResolver;
import dev.mikoto2000.rei.memory.service.MemoryConsolidatorService;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryReportsTest {
  @Test
  void summaryRendererPreservesPreviewAndApprovalWithoutSharedOutputState() {
    var consolidation = mock(MemoryConsolidatorService.class);
    var memories = mock(MemoryService.class);
    when(consolidation.summarizeCandidates())
        .thenReturn(new MemoryConsolidatorService.Summary(true, "A summary"));
    var command = new MemoryCommand.SummarizeCommand(consolidation, memories);
    assertEquals(
        "A summary" + System.lineSeparator() + "要約は未保存です。保存するには --approve を指定してください",
        command.render(false));
    verify(memories, never()).save(any());
    assertEquals("A summary" + System.lineSeparator() + "要約を記憶として保存しました", command.render(true));
    verify(memories).save(argThat(memory -> memory.content().equals("A summary")));
    assertTrue(command.render(false).contains("未保存"));
    verify(memories, times(1)).save(any());
  }

  @Test
  void approvalAliasesRejectDuplicateSpecificationLikeShell() {
    var command =
        new MemoryCommand.SummarizeCommand(
            mock(MemoryConsolidatorService.class), mock(MemoryService.class));
    assertThrows(
        picocli.CommandLine.OverwrittenOptionException.class,
        () -> new picocli.CommandLine(command).parseArgs("--approve=true", "--save=false"));
  }

  @Test
  void consolidationRendererHandlesNoCandidatesUsingTheExistingService() {
    var consolidation = mock(MemoryConsolidatorService.class);
    when(consolidation.extractCandidates()).thenReturn(List.of());
    var command =
        new MemoryCommand.ConsolidateCommand(
            consolidation,
            mock(MemoryService.class),
            mock(SensitiveInfoDetector.class),
            mock(MemoryConflictResolver.class));
    assertEquals("保存対象の会話履歴がありません", command.render(false));
  }
}
