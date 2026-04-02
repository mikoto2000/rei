package dev.mikoto2000.rei.vectordocument;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AsyncVectorDocumentServiceTest {

  @Test
  void addAsyncPrintsCompletionMessage() throws Exception {
    VectorDocumentService vectorDocumentService = Mockito.mock(VectorDocumentService.class);
    when(vectorDocumentService.add(List.of("docs/spec.md"))).thenReturn(List.of(
        new VectorDocumentEntry("doc-1", "/tmp/docs/spec.md", 3, "2026-03-28T00:00:00Z")));
    AsyncVectorDocumentService service = new AsyncVectorDocumentService(vectorDocumentService);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      service.addAsync(List.of("docs/spec.md"));
    } finally {
      System.setOut(originalOut);
    }

    verify(vectorDocumentService).add(List.of("docs/spec.md"));
    assertTrue(out.toString().contains("追加完了: doc-1"));
  }

  @Test
  void addAsyncPrintsFailureMessage() throws Exception {
    VectorDocumentService vectorDocumentService = Mockito.mock(VectorDocumentService.class);
    doThrow(new IOException("boom")).when(vectorDocumentService).add(List.of("docs/spec.md"));
    AsyncVectorDocumentService service = new AsyncVectorDocumentService(vectorDocumentService);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      service.addAsync(List.of("docs/spec.md"));
    } finally {
      System.setOut(originalOut);
    }

    verify(vectorDocumentService).add(List.of("docs/spec.md"));
    assertTrue(out.toString().contains("追加失敗"));
    assertTrue(out.toString().contains("docs/spec.md"));
  }
}
