package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.vectordocument.*;
class VectorDocumentControllerTest {
  @Test void explicitDocumentsAreQueuedWithoutBackendGlobExpansion() {
    var vectors=mock(VectorDocumentService.class);var background=mock(AsyncVectorDocumentService.class);var api=new VectorDocumentController(vectors,background);
    assertEquals("追加処理を開始: docs/a.txt",api.add(new VectorDocumentController.Documents(List.of("docs/a.txt"))).text());verify(background).addAsync(List.of("docs/a.txt"));
    assertThrows(IllegalArgumentException.class,()->api.add(new VectorDocumentController.Documents(List.of("docs/*"))));
    assertThrows(IllegalArgumentException.class,()->api.add(new VectorDocumentController.Documents(List.of())));
    verifyNoInteractions(vectors);
  }
  @Test void searchAndDeleteUseTheExistingServiceAndShellDisplay() {
    var vectors=mock(VectorDocumentService.class);var api=new VectorDocumentController(vectors,mock(AsyncVectorDocumentService.class));
    when(vectors.search("research",4,.5,"paper")).thenReturn(List.of());assertEquals("一致する文書はありません",api.search("research",4,.5,"paper").text());
    when(vectors.deleteByDocId("document")).thenReturn(true);assertEquals("削除: document",api.delete(new VectorDocumentController.Deletion("document",null)).text());
    assertThrows(IllegalArgumentException.class,()->api.delete(new VectorDocumentController.Deletion("document","paper")));
  }
}
