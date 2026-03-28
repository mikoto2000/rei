package dev.mikoto2000.rei.vectordocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VectorDocumentToolsTest {

  @Test
  void vectorDocumentAddDelegatesToService() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    VectorDocumentTools tools = new VectorDocumentTools(service);
    List<VectorDocumentEntry> expected = List.of(new VectorDocumentEntry("doc-1", "/tmp/docs/spec.md", 2, "2026-03-28T00:00:00Z"));
    when(service.add(List.of("/tmp/docs/spec.md"))).thenReturn(expected);

    List<VectorDocumentEntry> actual = tools.vectorDocumentAdd(List.of("/tmp/docs/spec.md"));

    assertEquals(expected, actual);
    verify(service).add(List.of("/tmp/docs/spec.md"));
  }

  @Test
  void vectorDocumentSearchDelegatesToService() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    VectorDocumentTools tools = new VectorDocumentTools(service);
    List<VectorDocumentSearchResult> expected = List.of(new VectorDocumentSearchResult("doc-1", "/tmp/docs/spec.md", 0, 0.91d, "Spring AI guide"));
    when(service.search("spring ai", 3, 0.4d, "/tmp/docs/spec.md")).thenReturn(expected);

    List<VectorDocumentSearchResult> actual = tools.vectorDocumentSearch("spring ai", 3, 0.4d, "/tmp/docs/spec.md");

    assertEquals(expected, actual);
    verify(service).search("spring ai", 3, 0.4d, "/tmp/docs/spec.md");
  }

  @Test
  void vectorDocumentListDelegatesToService() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    VectorDocumentTools tools = new VectorDocumentTools(service);
    List<VectorDocumentEntry> expected = List.of(new VectorDocumentEntry("doc-1", "/tmp/docs/spec.md", 2, "2026-03-28T00:00:00Z"));
    when(service.list()).thenReturn(expected);

    List<VectorDocumentEntry> actual = tools.vectorDocumentList();

    assertEquals(expected, actual);
    verify(service).list();
  }

  @Test
  void vectorDocumentDeleteByDocIdDelegatesToService() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    VectorDocumentTools tools = new VectorDocumentTools(service);
    when(service.deleteByDocId("doc-1")).thenReturn(true);

    boolean deleted = tools.vectorDocumentDeleteByDocId("doc-1");

    assertEquals(true, deleted);
    verify(service).deleteByDocId("doc-1");
  }

  @Test
  void vectorDocumentDeleteBySourceDelegatesToService() throws Exception {
    VectorDocumentService service = Mockito.mock(VectorDocumentService.class);
    VectorDocumentTools tools = new VectorDocumentTools(service);
    when(service.deleteBySource("/tmp/docs/spec.md")).thenReturn(1);

    int deleted = tools.vectorDocumentDeleteBySource("/tmp/docs/spec.md");

    assertEquals(1, deleted);
    verify(service).deleteBySource("/tmp/docs/spec.md");
  }
}
