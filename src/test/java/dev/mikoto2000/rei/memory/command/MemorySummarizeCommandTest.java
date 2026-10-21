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

/** 記憶要約コマンドの結果と状態の契約を検証するテスト。 */
class MemorySummarizeCommandTest {

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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

  /** 長期記憶の活用で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
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
