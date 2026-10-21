package dev.mikoto2000.rei.memory.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.memory.service.MemoryExporter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import picocli.CommandLine;

/** 記憶書き出しコマンドの結果と状態の契約を検証するテスト。 */
class MemoryExportCommandTest {

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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
}
