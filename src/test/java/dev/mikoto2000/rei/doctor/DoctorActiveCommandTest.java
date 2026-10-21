package dev.mikoto2000.rei.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.mikoto2000.rei.application.session.ShellConversationService;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** 環境診断活動中コマンドの結果と状態の契約を検証するテスト。 */
class DoctorActiveCommandTest {
  /** 明示的な活動中確認について、既存の実行待ちを利用することを確認する。 */
  @Test
  void explicitActiveCheckUsesExistingQueueAndPrintsPlanBeforeSubmission() {
    var passive = mock(DoctorService.class);
    var queue = mock(ShellConversationService.class);
    var output = new StringWriter();
    var command = new CommandLine(new DoctorCommand(passive, queue));
    command.setOut(new PrintWriter(output));
    doAnswer(
            call -> {
              assertTrue(output.toString().contains("connectivity"));
              return null;
            })
        .when(queue)
        .submit(anyString());
    assertEquals(0, command.execute("--check", "connectivity", "--details"));
    verify(queue).submit("/doctor --check connectivity --details");
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(passive);
  }

  /** 実行環境の診断で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidArgumentsCannotLeakValuesOrSubmit() {
    var passive = mock(DoctorService.class);
    var queue = mock(ShellConversationService.class);
    var errors = new StringWriter();
    var command = new CommandLine(new DoctorCommand(passive, queue));
    command.setErr(new PrintWriter(errors));
    assertEquals(2, command.execute("--check", "TOKEN-SECRET"));
    assertFalse(errors.toString().contains("TOKEN-SECRET"));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(passive, queue);
  }
}
