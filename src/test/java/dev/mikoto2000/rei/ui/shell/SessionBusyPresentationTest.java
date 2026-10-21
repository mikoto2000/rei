package dev.mikoto2000.rei.ui.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.application.run.SessionBusyException;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** 会話使用中表示用の情報の結果と状態の契約を検証するテスト。 */
class SessionBusyPresentationTest {
  /** 検証条件を再現するための使用中コマンド。 */
  @CommandLine.Command(name = "chat")
  static class BusyCommand implements Runnable {
    /** 検証に使う状態。 */
    final String status;

    /** 検証条件に必要な値を受け取り、BusyCommandを用意する。 */
    BusyCommand(String status) {
      this.status = status;
    }

    /** 検証用に、ターミナルでの対話の操作を実行する。 */
    public void run() {
      throw new SessionBusyException("project", "session", "blocked-run", status);
    }
  }

  /** ターミナルでの対話の操作が、指定された権限・承認条件に従うことを確認する。 */
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

  /** ターミナルでの対話の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void externalWaitDoesNotSuggestAnApprovalOperation() {
    String output = execute("WAITING");
    assertTrue(output.contains("外部応答待ち"));
    assertFalse(output.contains("/approval approve"));
  }

  /** ターミナルでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unknownStatusOffersInspectionAndAnotherSession() {
    String output = execute(null);
    assertTrue(output.contains("状態: 不明"));
    assertTrue(output.contains("/runs"));
    assertTrue(output.contains("/new"));
  }

  /** 検証用に、要求された処理を実行する。 */
  private String execute(String status) {
    var command = new CommandLine(new BusyCommand(status));
    var output = new StringWriter();
    command.setErr(new PrintWriter(output));
    ShellProjectCommands.installBusyHandler(command);
    assertEquals(2, command.execute());
    return output.toString();
  }
}
