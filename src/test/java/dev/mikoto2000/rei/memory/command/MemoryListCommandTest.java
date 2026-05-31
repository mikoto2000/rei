package dev.mikoto2000.rei.memory.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.MemoryService;
import picocli.CommandLine;

class MemoryListCommandTest {

  @Test
  void listShowsEmptyMessageWhenNoMemory() {
    MemoryService service = Mockito.mock(MemoryService.class);
    when(service.listActiveWithExpiryCheck()).thenReturn(List.of());

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new MemoryCommand.ListCommand(service)).execute();
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("保存済みの記憶はありません"));
  }

  @Test
  void listShowsPreviewWhenMemoriesExist() {
    MemoryService service = Mockito.mock(MemoryService.class);
    when(service.listActiveWithExpiryCheck()).thenReturn(List.of(
        new Memory("1", "abc", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM, MemoryStatus.ACTIVE, 0.8d, null, null, null)));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new MemoryCommand.ListCommand(service)).execute();
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("1 | KNOWLEDGE | abc"));
  }
}
