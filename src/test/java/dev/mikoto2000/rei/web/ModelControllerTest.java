package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.service.OpenAiCompatibleModelUnloadService;
import dev.mikoto2000.rei.core.service.OpenAiCompatibleModelsService;
import org.junit.jupiter.api.Test;

/** モデル受付窓口の結果と状態の契約を検証するテスト。 */
class ModelControllerTest {
  /** 一覧および選択内容について、シェルモデルサービス一覧を再利用することを確認する。 */
  @Test
  void listAndSelectionReuseShellModelServicesAndSkipUnloadingUnchangedModel() {
    var models = mock(OpenAiCompatibleModelsService.class);
    var holder = mock(ModelHolderService.class);
    var unload = mock(OpenAiCompatibleModelUnloadService.class);
    when(models.listModels()).thenReturn(java.util.List.of("one", "two"));
    when(holder.get()).thenReturn("one");
    var controller = new ModelController(models, holder, unload);
    assertEquals(java.util.List.of("one", "two"), controller.list().models());
    assertEquals("one", controller.current().model());
    controller.select(new ModelController.Selection("one"));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(unload);
    controller.select(new ModelController.Selection("two"));
    verify(unload).unload("one");
    verify(holder).set("two");
  }

  /** 選択内容形式を許可することを確認する。 */
  @Test
  void unloadFailureStillAllowsSelectionAsInShellAndInvalidModelsNeverReachServices() {
    var models = mock(OpenAiCompatibleModelsService.class);
    var holder = mock(ModelHolderService.class);
    var unload = mock(OpenAiCompatibleModelUnloadService.class);
    when(holder.get()).thenReturn("one");
    doThrow(new IllegalStateException("fixture")).when(unload).unload("one");
    new ModelController(models, holder, unload).select(new ModelController.Selection("two"));
    verify(holder).set("two");
    for (String value : new String[] {"", " ", "a\nb", "x".repeat(1025)})
      assertThrows(IllegalArgumentException.class, () -> new ModelController.Selection(value));
  }
}
