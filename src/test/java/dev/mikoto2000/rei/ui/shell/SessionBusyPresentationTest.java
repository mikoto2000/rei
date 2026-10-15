package dev.mikoto2000.rei.ui.shell;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.application.run.SessionBusyException;
import java.io.*;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class SessionBusyPresentationTest {
  @CommandLine.Command(name = "chat")
  static class BusyCommand implements Runnable {
    final String status;

    BusyCommand(String status) {
      this.status = status;
    }

    public void run() {
      throw new SessionBusyException("project", "session", "blocked-run", status);
    }
  }

  @Test
  void approvalWaitShowsActionsAndIdentifiersWithoutAStackTrace() {
    String output = execute("WAITING_APPROVAL");
    for (String text :
        new String[] {
          "Run ID: blocked-run",
          "WAITING_APPROVAL",
          "/approval list",
          "/approval show",
          "--continue",
          "/approval deny",
          "/new",
          "受け付けていません"
        }) assertTrue(output.contains(text), text);
    assertFalse(output.contains("SessionBusyException"));
    assertFalse(output.contains("at dev."));
  }

  @Test
  void externalWaitDoesNotSuggestAnApprovalOperation() {
    String output = execute("WAITING");
    assertTrue(output.contains("外部応答待ち"));
    assertFalse(output.contains("/approval approve"));
  }

  @Test
  void unknownStatusOffersInspectionAndAnotherSession() {
    String output = execute(null);
    assertTrue(output.contains("状態: 不明"));
    assertTrue(output.contains("/runs"));
    assertTrue(output.contains("/new"));
  }

  private String execute(String status) {
    var command = new CommandLine(new BusyCommand(status));
    var output = new StringWriter();
    command.setErr(new PrintWriter(output));
    ShellProjectCommands.installBusyHandler(command);
    assertEquals(2, command.execute());
    return output.toString();
  }
}
