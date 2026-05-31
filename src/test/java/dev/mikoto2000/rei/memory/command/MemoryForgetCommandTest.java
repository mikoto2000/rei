package dev.mikoto2000.rei.memory.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.MemoryService;
import picocli.CommandLine;

class MemoryForgetCommandTest {

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

  @Test
  void forgetShowsValidationForBlankId() {
    MemoryService service = Mockito.mock(MemoryService.class);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new MemoryCommand.ForgetCommand(service)).execute("");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }
    assertTrue(out.toString().contains("有効な記憶 ID を入力してください"));
  }

  @Test
  void forgetShowsAlreadyDeletedMessage() {
    MemoryService service = Mockito.mock(MemoryService.class);
    Memory deleted = new Memory("deleted", "x", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM, MemoryStatus.DELETED, 0.8d,
        null, null, null);
    when(service.findById("deleted")).thenReturn(Optional.of(deleted));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new MemoryCommand.ForgetCommand(service)).execute("deleted");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }
    assertTrue(out.toString().contains("指定された記憶はすでに削除済みです"));
  }
}
