package dev.mikoto2000.rei.doctor;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DoctorCommandTest {
  @Test void defaultAndDetailsOnlyPerformPassiveDiagnosis() {
    var service = mock(DoctorService.class); when(service.passive(anyBoolean())).thenReturn(List.of());
    var command = new CommandLine(new DoctorCommand(service)); command.setOut(new PrintWriter(new StringWriter()));
    assertEquals(0, command.execute()); verify(service).passive(false);
    assertEquals(0, command.execute("--details")); verify(service).passive(true);
    verifyNoMoreInteractions(service);
  }
  @Test void unknownActiveCheckIsRejectedWithoutDiagnosis() {
    var service = mock(DoctorService.class); var command = new CommandLine(new DoctorCommand(service));
    command.setErr(new PrintWriter(new StringWriter()));
    assertNotEquals(0, command.execute("--check", "inference")); verifyNoInteractions(service);
  }
  @Test void rootRegistersDoctorAndCanDisableItWithoutRemovingOtherCommands() {
    var root = new dev.mikoto2000.rei.ui.shell.RootCommand();
    var command = new CommandLine(root); assertTrue(command.getSubcommands().containsKey("doctor"));
    org.springframework.test.util.ReflectionTestUtils.setField(root, "doctorEnabled", false);
    root.configureCommands(command);
    assertFalse(command.getSubcommands().containsKey("doctor"));
    assertTrue(command.getSubcommands().containsKey("agent"));
  }

  @Test void unexpectedConfigurationFailureCannotLeakExceptionDetails() {
    var service = mock(DoctorService.class);
    when(service.passive(anyBoolean())).thenThrow(new IllegalArgumentException("URL-SECRET token=KEY-SECRET"));
    var error = new StringWriter(); var command = new CommandLine(new DoctorCommand(service)); command.setErr(new PrintWriter(error));
    assertEquals(2, command.execute());
    assertFalse(error.toString().contains("URL-SECRET")); assertFalse(error.toString().contains("KEY-SECRET"));
  }
}
