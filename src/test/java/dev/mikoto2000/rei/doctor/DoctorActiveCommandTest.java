package dev.mikoto2000.rei.doctor;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import java.io.*;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class DoctorActiveCommandTest {
 @Test void explicitActiveCheckUsesExistingQueueAndPrintsPlanBeforeSubmission() {
  var passive=mock(DoctorService.class);var queue=mock(ShellConversationService.class);
  var output=new StringWriter();var command=new CommandLine(new DoctorCommand(passive,queue));
  command.setOut(new PrintWriter(output));
  doAnswer(call->{assertTrue(output.toString().contains("connectivity"));return null;}).when(queue).submit(anyString());
  assertEquals(0,command.execute("--check","connectivity","--details"));
  verify(queue).submit("/doctor --check connectivity --details");verifyNoInteractions(passive);
 }
 @Test void invalidArgumentsCannotLeakValuesOrSubmit() {
  var passive=mock(DoctorService.class);var queue=mock(ShellConversationService.class);
  var errors=new StringWriter();var command=new CommandLine(new DoctorCommand(passive,queue));command.setErr(new PrintWriter(errors));
  assertEquals(2,command.execute("--check","TOKEN-SECRET"));assertFalse(errors.toString().contains("TOKEN-SECRET"));
  verifyNoInteractions(passive,queue);
 }
}
