package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.MemoryLifecyclePayload;
import dev.mikoto2000.rei.memory.command.MemoryCommandSupport;
import dev.mikoto2000.rei.memory.command.SleepCommand;
import dev.mikoto2000.rei.memory.service.AutoSleepService;
import dev.mikoto2000.rei.memory.service.SleepService;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AutoSleepCommandTest {
  @Test
  void commandsUseSelectedSessionWithoutCallingSleep() {
    var projects = mock(ProjectService.class);
    var sleep = mock(SleepService.class);
    var auto = mock(AutoSleepService.class);
    var project = "00000000-0000-0000-0000-000000000001";
    when(projects.currentSessionId()).thenReturn("selected");
    when(projects.currentContext()).thenReturn(new ProjectContext(project, "p", Path.of(".")));
    when(auto.status("selected", project)).thenReturn("MODEL_TIMEOUT 480");
    when(auto.resume("selected", project)).thenReturn("resumed");
    var command = new SleepCommand(sleep, mock(MemoryCommandSupport.class), projects, null);
    command.setAuto(auto);
    var output = new StringWriter();
    var cli = new picocli.CommandLine(command);
    cli.setOut(new PrintWriter(output));
    assertEquals(0, cli.execute("auto", "status"));
    assertTrue(output.toString().contains("MODEL_TIMEOUT"));
    assertEquals(0, cli.execute("auto", "resume"));
    verify(auto).resume("selected", project);
    verifyNoInteractions(sleep);
  }

  @Test
  void stopEventDisplaysBackgroundSessionAndReason() {
    var text = new StringBuilder();
    var renderer =
        new dev.mikoto2000.rei.ui.shell.ShellAgentEventRenderer(
            new dev.mikoto2000.rei.ui.shell.ShellEventOutput() {
              public void print(String s) {
                text.append(s);
              }

              public void println(String s) {
                text.append(s).append('\n');
              }

              public void flush() {}
            });
    var event =
        new AgentEventFactory(java.time.Clock.systemUTC())
            .memory(
                AgentEventType.AUTO_SLEEP_STOPPED,
                "p",
                "background-session",
                new MemoryLifecyclePayload(
                    null,
                    false,
                    0,
                    0,
                    0,
                    "Auto Sleepを停止しました\nSession: background-session\nMODEL_TIMEOUT 480\n/sleep auto resume"));
    renderer.onEvent(event);
    assertTrue(text.toString().contains("background-session"));
    assertTrue(text.toString().contains("480"));
    assertEquals("background-session", event.sessionId());
  }
}
