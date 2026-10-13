package dev.mikoto2000.rei.ui.shell;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
class ShellFailureDiagnosticsTest {
  @CommandLine.Command(name="probe") static class Probe implements Callable<Integer> {
    final RuntimeException error;
    Probe(RuntimeException error) { this.error = error; }
    public Integer call() { throw error; }
  }
  @Test void uncaughtFailureShowsCauseAndHonoursExitMapper() {
    var text = new StringWriter(); var command = new CommandLine(new Probe(new IllegalStateException("operation failed", new IOException("disk full token=hidden"))));
    command.setErr(new PrintWriter(text, true)); command.setExitCodeExceptionMapper(error -> 42); ShellProjectCommands.installBusyHandler(command);
    assertEquals(42, command.execute()); assertTrue(text.toString().contains("operation failed → disk full token=[REDACTED]"));
    assertFalse(text.toString().contains("hidden")); assertFalse(text.toString().contains("at dev."));
  }
  @Test void interruptedFailureIsReportedAsCancelled() {
    var text = new StringWriter(); var command = new CommandLine(new Probe(new IllegalStateException("stop requested", new InterruptedException("user cancelled"))));
    command.setErr(new PrintWriter(text, true)); ShellProjectCommands.installBusyHandler(command);
    assertEquals(130, command.execute()); assertTrue(text.toString().contains("[cancelled] probe: stop requested → user cancelled"));
  }
  @Test void sessionFailureShowsUnderlyingStorageReason() {
    var service = mock(ShellConversationService.class); when(service.newConversation(null)).thenThrow(new IllegalStateException("database unavailable", new IOException("disk full")));
    var text = new StringWriter(); var command = new CommandLine(new NewConversationCommand(service)); command.setErr(new PrintWriter(text, true));
    assertEquals(1, command.execute()); assertTrue(text.toString().contains("database unavailable → disk full"));
  }
  @Test void activityFailureShowsSpecificBackendReason() {
    var capture = mock(dev.mikoto2000.rei.activity.ActivityCapture.class);
    doThrow(new IllegalStateException("capture unavailable token=hidden")).when(capture).pause();
    var command = new CommandLine(new dev.mikoto2000.rei.activity.ActivityCommand(null, capture, null));
    var text = new StringWriter(); command.setErr(new PrintWriter(text, true));
    assertEquals(1, command.execute("pause"));
    assertTrue(text.toString().contains("capture unavailable token=[REDACTED]"));
  }
  @Test void cancelDistinguishesAcceptedRequestFromNoActiveWork() {
    var service = mock(CommandCancellationService.class); when(service.cancelCurrentProject()).thenReturn(true, false);
    var text = new StringWriter(); var command = new CommandLine(new CancelCommand(service)); command.setOut(new PrintWriter(text, true));
    assertEquals(0, command.execute()); assertTrue(text.toString().contains("停止を要求しました")); text.getBuffer().setLength(0);
    assertEquals(0, command.execute()); assertTrue(text.toString().contains("停止対象の実行がない"));
  }
}
