package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.vectordocument.VectorDocumentEntry;
import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.vectordocument.VectorDocumentService;
import picocli.CommandLine;

class EmbedCommandTest {

  @Test
  void positionalArgumentsDelegateToAddAlias() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    when(service.add(List.of("docs/a.txt", "docs/b.md"))).thenReturn(List.of(
        new VectorDocumentEntry("doc-1", "/tmp/docs/a.txt", 1, "2026-03-28T00:00:00Z"),
        new VectorDocumentEntry("doc-2", "/tmp/docs/b.md", 2, "2026-03-28T00:00:00Z")));

    int exitCode = newCommand(service).execute("docs/a.txt", "docs/b.md");

    assertEquals(0, exitCode);
    verify(service).add(List.of("docs/a.txt", "docs/b.md"));
  }

  @Test
  void addCommandDelegatesToService() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    when(service.add(List.of("docs/spec.md"))).thenReturn(List.of(
        new VectorDocumentEntry("doc-1", "/tmp/docs/spec.md", 3, "2026-03-28T00:00:00Z")));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service).execute("add", "docs/spec.md");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    verify(service).add(List.of("docs/spec.md"));
    assertTrue(out.toString().contains("doc-1"));
  }

  @Test
  void searchCommandPrintsHits() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    when(service.search("spring ai", 2, 0.4d, "/tmp/docs/spec.md")).thenReturn(List.of(
        new VectorDocumentSearchResult("doc-1", "/tmp/docs/spec.md", 0, 0.91d, "Spring AI guide")));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service).execute("search", "--top-k", "2", "--threshold", "0.4", "--source", "/tmp/docs/spec.md", "spring ai");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    verify(service).search("spring ai", 2, 0.4d, "/tmp/docs/spec.md");
    assertTrue(out.toString().contains("Spring AI guide"));
  }

  @Test
  void listCommandPrintsDocuments() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    when(service.list()).thenReturn(List.of(
        new VectorDocumentEntry("doc-1", "/tmp/docs/spec.md", 3, "2026-03-28T00:00:00Z")));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service).execute("list");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    verify(service).list();
    assertTrue(out.toString().contains("/tmp/docs/spec.md"));
  }

  @Test
  void deleteCommandSupportsDocIdAndSource() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    when(service.deleteByDocId("doc-1")).thenReturn(true);
    when(service.deleteBySource("/tmp/docs/spec.md")).thenReturn(1);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertEquals(0, newCommand(service).execute("delete", "--doc-id", "doc-1"));
      assertEquals(0, newCommand(service).execute("delete", "--source", "/tmp/docs/spec.md"));
    } finally {
      System.setOut(originalOut);
    }

    verify(service).deleteByDocId("doc-1");
    verify(service).deleteBySource("/tmp/docs/spec.md");
    assertTrue(out.toString().contains("削除"));
  }

  private CommandLine newCommand(VectorDocumentService service) {
    return new CommandLine(new EmbedCommand(service), new CommandLine.IFactory() {
      @Override
      public <K> K create(Class<K> cls) throws Exception {
        if (cls == EmbedCommand.class) {
          return cls.cast(new EmbedCommand(service));
        }
        if (cls == EmbedCommand.AddCommand.class) {
          return cls.cast(new EmbedCommand.AddCommand(service));
        }
        if (cls == EmbedCommand.SearchCommand.class) {
          return cls.cast(new EmbedCommand.SearchCommand(service));
        }
        if (cls == EmbedCommand.ListCommand.class) {
          return cls.cast(new EmbedCommand.ListCommand(service));
        }
        if (cls == EmbedCommand.DeleteCommand.class) {
          return cls.cast(new EmbedCommand.DeleteCommand(service));
        }
        return CommandLine.defaultFactory().create(cls);
      }
    });
  }
}
