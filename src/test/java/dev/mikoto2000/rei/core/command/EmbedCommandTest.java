package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import dev.mikoto2000.rei.vectordocument.VectorDocumentEntry;
import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.vectordocument.VectorDocumentService;
import picocli.CommandLine;

class EmbedCommandTest {

  @TempDir
  Path tempDir;

  @Test
  void positionalArgumentsQueueAsyncEmbedding() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    AsyncVectorDocumentService asyncService = Mockito.mock(AsyncVectorDocumentService.class);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    int exitCode;
    try {
      exitCode = newCommand(service, asyncService).execute("docs/a.txt", "docs/b.md");
    } finally {
      System.setOut(originalOut);
    }

    assertEquals(0, exitCode);
    verify(asyncService).addAsync(List.of("docs/a.txt", "docs/b.md"));
    assertTrue(out.toString().contains("追加処理を開始"));
  }

  @Test
  void addCommandQueuesAsyncEmbedding() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    AsyncVectorDocumentService asyncService = Mockito.mock(AsyncVectorDocumentService.class);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service, asyncService).execute("add", "docs/spec.md");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    verify(asyncService).addAsync(List.of("docs/spec.md"));
    assertTrue(out.toString().contains("docs/spec.md"));
  }

  @Test
  void addCommandExpandsWildcardPatterns() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    AsyncVectorDocumentService asyncService = Mockito.mock(AsyncVectorDocumentService.class);
    Path docsDir = Files.createDirectories(tempDir.resolve("docs"));
    Path markdown = Files.writeString(docsDir.resolve("spec.md"), "# spec");
    Path text = Files.writeString(docsDir.resolve("note.txt"), "note");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    String originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.toString());
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service, asyncService).execute("add", "docs/*");
      assertEquals(0, exitCode);
    } finally {
      System.setProperty("user.dir", originalUserDir);
      System.setOut(originalOut);
    }

    verify(asyncService).addAsync(List.of(
        text.toString(),
        markdown.toString()));
    assertTrue(out.toString().contains("docs/*"));
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
      int exitCode = newCommand(service, Mockito.mock(AsyncVectorDocumentService.class)).execute("search", "--top-k", "2", "--threshold", "0.4", "--source", "/tmp/docs/spec.md", "spring ai");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    verify(service).search("spring ai", 2, 0.4d, "/tmp/docs/spec.md");
    String output = out.toString();
    assertTrue(output.contains("Spring AI guide"));
    assertTrue(output.contains("score=0.910"));
  }

  @Test
  void listCommandPrintsDocumentsGroupedBySource() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    when(service.list()).thenReturn(List.of(
        new VectorDocumentEntry("doc-1", "/tmp/docs/spec.md", 3, "2026-03-28T00:00:00Z"),
        new VectorDocumentEntry("doc-2", "/tmp/docs/spec.md", 2, "2026-03-29T00:00:00Z"),
        new VectorDocumentEntry("doc-3", "/tmp/docs/other.md", 1, "2026-03-27T00:00:00Z")));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service, Mockito.mock(AsyncVectorDocumentService.class)).execute("list");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    verify(service).list();
    String output = out.toString();
    assertTrue(output.contains("/tmp/docs/spec.md | docs=2 | chunks=5 | latest=2026-03-29T00:00:00Z"));
    assertTrue(output.contains("/tmp/docs/other.md | docs=1 | chunks=1 | latest=2026-03-27T00:00:00Z"));
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
      assertEquals(0, newCommand(service, Mockito.mock(AsyncVectorDocumentService.class)).execute("delete", "--doc-id", "doc-1"));
      assertEquals(0, newCommand(service, Mockito.mock(AsyncVectorDocumentService.class)).execute("delete", "--source", "/tmp/docs/spec.md"));
    } finally {
      System.setOut(originalOut);
    }

    verify(service).deleteByDocId("doc-1");
    verify(service).deleteBySource("/tmp/docs/spec.md");
    assertTrue(out.toString().contains("削除"));
  }

  @Test
  void useSubcommandIsNotAvailable() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    assertFalse(newCommand(service, Mockito.mock(AsyncVectorDocumentService.class)).getSubcommands().containsKey("use"));
  }

  private CommandLine newCommand(VectorDocumentService service, AsyncVectorDocumentService asyncService) {
    return new CommandLine(new EmbedCommand(service, asyncService), new CommandLine.IFactory() {
      @Override
      public <K> K create(Class<K> cls) throws Exception {
        if (cls == EmbedCommand.class) {
          return cls.cast(new EmbedCommand(service, asyncService));
        }
        if (cls == EmbedCommand.AddCommand.class) {
          return cls.cast(new EmbedCommand.AddCommand(asyncService));
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
