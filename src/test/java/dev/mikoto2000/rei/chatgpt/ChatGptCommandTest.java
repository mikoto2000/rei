package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import picocli.CommandLine;

/** チャットGPTコマンドの結果と状態の契約を検証するテスト。 */
class ChatGptCommandTest {
  /** ChatGPTとの連携で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void disabledConfigurationFailsWithoutOpeningBrowserOrNetwork() {
    var command = new CommandLine(new ChatGptCommand());
    var output = new StringWriter();
    command.setOut(new PrintWriter(output));
    for (String action : List.of("login", "status", "models", "logout"))
      assertEquals(2, command.execute(action));
    assertFalse(output.toString().contains("Bearer"));
  }

  /** ChatGPTとの連携の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void phaseZeroProbeCannotSendWithoutProjectPermission() {
    @SuppressWarnings("unchecked")
    var provider = (ObjectProvider<PlanClient>) mock(ObjectProvider.class);
    var client = mock(PlanClient.class);
    when(provider.getIfAvailable()).thenReturn(client);
    var command = new CommandLine(new ChatGptCommand(null, provider, null));
    command.setOut(new PrintWriter(new StringWriter()));
    assertEquals(2, command.execute("infer", "--model", "model", "task"));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(client);
  }
}
