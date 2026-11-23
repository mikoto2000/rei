package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.service.OpenAiCompatibleModelsService;
import org.junit.jupiter.api.Test;

/** モデル受付窓口の結果と状態の契約を検証するテスト。 */
class ModelControllerTest {
  /** 一覧および選択内容について、シェルモデルサービス一覧を再利用することを確認する。 */
  @Test
  void listAndSelectionReuseShellModelServices() {
    var models = mock(OpenAiCompatibleModelsService.class);
    var holder = new ModelHolderService("one");
    when(models.listModels()).thenReturn(java.util.List.of("one", "two"));
    var controller = new ModelController(models, holder);
    assertEquals(java.util.List.of("one", "two"), controller.list().models());
    assertEquals("one", controller.current().model());
    assertEquals("one", controller.select(new ModelController.Selection("one")).model());
    assertEquals("one", holder.get());
    assertEquals("two", controller.select(new ModelController.Selection("two")).model());
    assertEquals("two", holder.get());
    assertEquals("two", controller.current().model());
  }

  /** 不正なモデル名が拒否されることを確認する。 */
  @Test
  void invalidModelsAreRejected() {
    for (String value : new String[] {"", " ", "a\nb", "x".repeat(1025)})
      assertThrows(IllegalArgumentException.class, () -> new ModelController.Selection(value));
  }
}
