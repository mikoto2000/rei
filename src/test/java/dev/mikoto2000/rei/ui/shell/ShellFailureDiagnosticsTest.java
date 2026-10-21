package dev.mikoto2000.rei.ui.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** シェル失敗情報診断情報の結果と状態の契約を検証するテスト。 */
class ShellFailureDiagnosticsTest {
  /** 検証条件を再現するための利用可否の確認。 */
  @CommandLine.Command(name = "probe")
  static class Probe implements Callable<Integer> {
    /** 検証に使うエラー。 */
    final RuntimeException error;

    /** 検証条件に必要な値を受け取り、Probeを用意する。 */
    Probe(RuntimeException error) {
      this.error = error;
    }

    /** 検証用に、ターミナルでの対話の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      throw error;
    }
  }

  /** ターミナルでの対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void uncaughtFailureShowsCauseAndHonoursExitMapper() {
    var text = new StringWriter();
    var command =
        new CommandLine(
            new Probe(
                new IllegalStateException(
                    "operation failed", new IOException("disk full token=hidden"))));
    command.setErr(new PrintWriter(text, true));
    command.setExitCodeExceptionMapper(error -> 42);
    ShellProjectCommands.installBusyHandler(command);
    assertEquals(42, command.execute());
    assertTrue(text.toString().contains("operation failed → disk full token=[REDACTED]"));
    assertFalse(text.toString().contains("hidden"));
    assertFalse(text.toString().contains("at dev."));
  }

  /** ターミナルでの対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void interruptedFailureIsReportedAsCancelled() {
    var text = new StringWriter();
    var command =
        new CommandLine(
            new Probe(
                new IllegalStateException(
                    "stop requested", new InterruptedException("user cancelled"))));
    command.setErr(new PrintWriter(text, true));
    ShellProjectCommands.installBusyHandler(command);
    assertEquals(130, command.execute());
    assertTrue(text.toString().contains("[cancelled] probe: stop requested → user cancelled"));
  }

  /** ターミナルでの対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void sessionFailureShowsUnderlyingStorageReason() {
    var service = mock(ShellConversationService.class);
    when(service.newConversation(null))
        .thenThrow(new IllegalStateException("database unavailable", new IOException("disk full")));
    var text = new StringWriter();
    var command = new CommandLine(new NewConversationCommand(service));
    command.setErr(new PrintWriter(text, true));
    assertEquals(1, command.execute());
    assertTrue(text.toString().contains("database unavailable → disk full"));
  }

  /** ターミナルでの対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void activityFailureShowsSpecificBackendReason() {
    var capture = mock(dev.mikoto2000.rei.activity.ActivityCapture.class);
    doThrow(new IllegalStateException("capture unavailable token=hidden")).when(capture).pause();
    var command =
        new CommandLine(new dev.mikoto2000.rei.activity.ActivityCommand(null, capture, null));
    var text = new StringWriter();
    command.setErr(new PrintWriter(text, true));
    assertEquals(1, command.execute("pause"));
    assertTrue(text.toString().contains("capture unavailable token=[REDACTED]"));
  }

  /** ターミナルでの対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelDistinguishesAcceptedRequestFromNoActiveWork() {
    var service = mock(CommandCancellationService.class);
    when(service.cancelCurrentProject()).thenReturn(true, false);
    var text = new StringWriter();
    var command = new CommandLine(new CancelCommand(service));
    command.setOut(new PrintWriter(text, true));
    assertEquals(0, command.execute());
    assertTrue(text.toString().contains("停止を要求しました"));
    text.getBuffer().setLength(0);
    assertEquals(0, command.execute());
    assertTrue(text.toString().contains("停止対象の実行がない"));
  }
}
