package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 利用者入力サービスの結果と状態の契約を検証するテスト。 */
class UserInputServiceTest {

  /** 検証に使う入力一覧。 */
  private final UserInputService inputs = new UserInputService(new UserInputParser());

  /** 共通の実行基盤で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void preservesCanonicalShellSpecialCommands() {
    assertEquals(UserInputService.Kind.EXIT, inputs.interpret("/exit").kind());
    assertEquals(UserInputService.Kind.EXIT, inputs.interpret(" /quit ").kind());
    assertEquals(UserInputService.Kind.HELP, inputs.interpret("/help").kind());
    assertEquals(UserInputService.Kind.VERSION, inputs.interpret("/version").kind());
    assertEquals(UserInputService.Kind.PASTE, inputs.interpret("/paste").kind());
  }

  /** 空のチャットを保持することを確認する。 */
  @Test
  void preservesEmptyChatAndGeneralSlashBehavior() {
    assertEquals(UserInputService.Kind.EMPTY, inputs.interpret("  ").kind());
    assertEquals(UserInputService.Kind.CHAT, inputs.interpret(" hello ").kind());
    assertEquals(" hello ", inputs.interpret(" hello ").text());
    UserInputService.Input command = inputs.interpret("/project add \"C:\\work dir\"");
    assertEquals(UserInputService.Kind.COMMAND, command.kind());
    assertArrayEquals(new String[] {"project", "add", "C:\\work dir"}, command.arguments());
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void onlyExactSpecialCommandsReceiveSpecialMeaning() {
    assertEquals(UserInputService.Kind.COMMAND, inputs.interpret("/help extra").kind());
    assertEquals(UserInputService.Kind.COMMAND, inputs.interpret("/exit now").kind());
  }
}
