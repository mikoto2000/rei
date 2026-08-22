package dev.mikoto2000.rei.ui.tui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import dev.mikoto2000.rei.core.command.ChatCommand;
import dev.mikoto2000.rei.core.command.RootCommand;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventBus;
import picocli.CommandLine;

class AgentTuiLauncherCommandTest {

  @Test
  void capturesMultilineRootHelpForTuiPresentation() {
    @SuppressWarnings("unchecked")
    ObjectProvider<RootCommand> root = mock(ObjectProvider.class);
    when(root.getObject()).thenReturn(new RootCommand());
    AgentTuiLauncher launcher = new AgentTuiLauncher(mock(AgentEventBus.class), mock(ChatCommand.class),
        mock(CommandCancellationService.class), root, CommandLine.defaultFactory());

    String output = launcher.executeCommand(new String[] {"help"});

    assertTrue(output.contains("Usage:"));
    assertTrue(output.contains("tui"));
    assertTrue(output.lines().count() > 2);
  }

  @Test
  void capturesUnknownCommandError() {
    @SuppressWarnings("unchecked")
    ObjectProvider<RootCommand> root = mock(ObjectProvider.class);
    when(root.getObject()).thenReturn(new RootCommand());
    AgentTuiLauncher launcher = new AgentTuiLauncher(mock(AgentEventBus.class), mock(ChatCommand.class),
        mock(CommandCancellationService.class), root, CommandLine.defaultFactory());

    String output = launcher.executeCommand(new String[] {"does-not-exist"});
    assertTrue(!output.isBlank());
    assertTrue(output.contains("does-not-exist"));
  }
}
