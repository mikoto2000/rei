package dev.mikoto2000.rei.workcontext;
import java.io.*;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkCommandTest {
  @Test void shellShowHistoryAndUpdateUseSharedServiceAndCapturedSession() {
    var service=mock(WorkContextService.class);var projects=mock(ProjectService.class);var git=mock(WorkContextGit.class);
    var project=new ProjectContext(UUID.randomUUID().toString(),"other current",Path.of("."));
    when(projects.currentContext()).thenReturn(project);when(projects.currentSessionId()).thenReturn("session-owner");
    when(service.current(project.id())).thenReturn(Optional.empty());when(service.history(project.id(),100)).thenReturn(List.of());
    when(service.update("session-owner",null,true)).thenReturn(Optional.empty());
    var output=new StringWriter();var command=new WorkCommand(service,projects,git,mock(CommandCancellationService.class));var cli=new CommandLine(command);
    command.setShellOutput(new PrintWriter(output));
    assertEquals(0,cli.execute());assertTrue(output.toString().contains("まだありません"));
    assertEquals(0,cli.execute("show"));assertEquals(0,cli.execute("history"));assertEquals(0,cli.execute("update"));
    verify(service).update("session-owner",null,true);
  }
}
