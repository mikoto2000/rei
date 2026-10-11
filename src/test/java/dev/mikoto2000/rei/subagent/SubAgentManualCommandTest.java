package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class SubAgentManualCommandTest {
  @Test
  void omittedBackendUsesExistingLocalRunner() {
    var runner = mock(SubAgentRunner.class);
    when(runner.run("reviewer", "task", null))
        .thenReturn(
            new SubAgentResult(
                "reviewer",
                "run",
                SubAgentResult.Status.COMPLETED,
                "review",
                Instant.now(),
                Instant.now()));
    var parent = new SubAgentCommand(mock(SubAgentRegistry.class), null, null);
    parent.configureExecution(runner, null);
    var command = new CommandLine(parent);
    command.setOut(new PrintWriter(new StringWriter()));
    assertEquals(0, command.execute("run", "reviewer", "--task", "task"));
    verify(runner).run("reviewer", "task", null);
    verifyNoMoreInteractions(runner);
  }

  @Test
  void externalBackendRequiresExplicitModelCompletionConditionAndMaterials() {
    var runner = mock(SubAgentRunner.class);
    var parent = new SubAgentCommand(mock(SubAgentRegistry.class), null, null);
    parent.configureExecution(runner, null);
    var command = new CommandLine(parent);
    command.setOut(new PrintWriter(new StringWriter()));
    assertEquals(
        2, command.execute("run", "reviewer", "--backend", "chatgpt-plan", "--task", "task"));
    assertEquals(
        2,
        command.execute(
            "run",
            "reviewer",
            "--backend",
            "chatgpt-plan",
            "--model",
            "model",
            "--expected-result",
            "{}",
            "--context",
            "unscoped data",
            "--task",
            "task"));
    verifyNoInteractions(runner);
  }
}
