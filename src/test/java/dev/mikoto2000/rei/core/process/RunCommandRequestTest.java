package dev.mikoto2000.rei.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 実行コマンド要求の結果と状態の契約を検証するテスト。 */
class RunCommandRequestTest {

  /** 外部プロセスの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void defaultsExecutionModeToAuto() {
    RunCommandRequest request = new RunCommandRequest("echo hello", null, null);
    assertEquals(CommandExecutionMode.AUTO, request.mode());
  }

  /** 外部プロセスの管理で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void acceptsEveryExecutionModeCaseInsensitively() {
    assertEquals(CommandExecutionMode.AUTO, new RunCommandRequest("cmd", "AUTO", null).mode());
    assertEquals(
        CommandExecutionMode.FOREGROUND, new RunCommandRequest("cmd", "foreground", null).mode());
    assertEquals(
        CommandExecutionMode.BACKGROUND, new RunCommandRequest("cmd", "background", null).mode());
  }

  /** 空白のコマンドを受け付けないことを確認する。 */
  @Test
  void rejectsBlankCommandAndUnknownMode() {
    assertThrows(IllegalArgumentException.class, () -> new RunCommandRequest(" ", "auto", null));
    assertThrows(
        IllegalArgumentException.class, () -> new RunCommandRequest("cmd", "detached", null));
  }
}
