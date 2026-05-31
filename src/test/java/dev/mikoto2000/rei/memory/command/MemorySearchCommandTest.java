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

class MemorySearchCommandTest {

  @Test
  void searchShowsValidationForTooLongQuery() {
    MemoryService service = Mockito.mock(MemoryService.class);
    String longQuery = "a".repeat(201);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new MemoryCommand.SearchCommand(service)).execute(longQuery);
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("検索クエリは 200 文字以内で入力してください"));
  }

  @Test
  void searchShowsValidationForEmptyQuery() {
    MemoryService service = Mockito.mock(MemoryService.class);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new MemoryCommand.SearchCommand(service)).execute("");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("検索クエリを入力してください"));
  }

  @Test
  void searchShowsResultWhenFound() {
    MemoryService service = Mockito.mock(MemoryService.class);
    when(service.search("java", 10)).thenReturn(List.of(
        new Memory("1", "java tips", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM, MemoryStatus.ACTIVE, 0.8d, null, null, null)));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new MemoryCommand.SearchCommand(service)).execute("java");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("1 | KNOWLEDGE | java tips"));
  }

  @Test
  void searchShowsNoResultMessageWhenEmpty() {
    MemoryService service = Mockito.mock(MemoryService.class);
    when(service.search("java", 10)).thenReturn(List.of());

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new MemoryCommand.SearchCommand(service)).execute("java");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("該当する記憶が見つかりませんでした"));
  }
}
