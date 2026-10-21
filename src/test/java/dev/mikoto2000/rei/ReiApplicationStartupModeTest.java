package dev.mikoto2000.rei;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

/** れいアプリケーション起動時動作モードの結果と状態の契約を検証するテスト。 */
class ReiApplicationStartupModeTest {

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void allStartupArgumentsUseTheShellEntryPoint() throws Exception {
    ReiApplication app = mock(ReiApplication.class);

    ReiApplication.launch(app, new String[0]);
    verify(app).run(new String[0]);

    ReiApplication.launch(app, new String[] {"--tui"});
    verify(app).run(new String[] {"--tui"});
  }
}
