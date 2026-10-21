package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import dev.mikoto2000.rei.vectordocument.VectorDocumentService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ベクトル文書受付窓口の結果と状態の契約を検証するテスト。 */
class VectorDocumentControllerTest {
  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void explicitDocumentsAreQueuedWithoutBackendGlobExpansion() {
    var vectors = mock(VectorDocumentService.class);
    var background = mock(AsyncVectorDocumentService.class);
    var api = new VectorDocumentController(vectors, background);
    assertEquals(
        "追加処理を開始: docs/a.txt",
        api.add(new VectorDocumentController.Documents(List.of("docs/a.txt"))).text());
    verify(background).addAsync(List.of("docs/a.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> api.add(new VectorDocumentController.Documents(List.of("docs/*"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> api.add(new VectorDocumentController.Documents(List.of())));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(vectors);
  }

  /** 検索およびについて、利用既存のサービスを削除することを確認する。 */
  @Test
  void searchAndDeleteUseTheExistingServiceAndShellDisplay() {
    var vectors = mock(VectorDocumentService.class);
    var api = new VectorDocumentController(vectors, mock(AsyncVectorDocumentService.class));
    when(vectors.search("research", 4, .5, "paper")).thenReturn(List.of());
    assertEquals("一致する文書はありません", api.search("research", 4, .5, "paper").text());
    when(vectors.deleteByDocId("document")).thenReturn(true);
    assertEquals(
        "削除: document", api.delete(new VectorDocumentController.Deletion("document", null)).text());
    assertThrows(
        IllegalArgumentException.class,
        () -> api.delete(new VectorDocumentController.Deletion("document", "paper")));
  }
}
