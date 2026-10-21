package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.core.policy.InvalidToolArgumentsException;
import dev.mikoto2000.rei.core.process.RunCommandRequest;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** シェルファイル読み取り実行の結果と状態の契約を検証するテスト。 */
class ShellFileReadExecutionTest {
  /** 共通の実行基盤で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"auto", "foreground", "background"})
  void directToolRejectsBeforeStartingAnyProcess(String mode) {
    var request = new RunCommandRequest("git status; Get-Content docs/a.md", mode, 30);
    assertThrows(
        InvalidToolArgumentsException.class,
        () -> new Tools().runCommand(request, Path.of("."), Duration.ZERO));
  }
}
