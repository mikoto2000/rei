package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** クライアント履歴の結果と状態の契約を検証するテスト。 */
class ClientHistoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 軽量CLIでの対話で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void concurrentClientsOwnDifferentFilesAndNeverMerge() throws Exception {
    Path firstFile, secondFile;
    try (var first = new ClientHistory(directory);
        var second = new ClientHistory(directory)) {
      first.record("hello");
      second.record("other");
      assertNotEquals(first.file(), second.file());
      firstFile = first.file();
      secondFile = second.file();
    }
    assertEquals(java.util.List.of("hello"), Files.readAllLines(firstFile));
    assertEquals(java.util.List.of("other"), Files.readAllLines(secondFile));
  }

  /** 軽量CLIでの対話で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void explicitSensitiveInputAndDisabledHistoryAreNeverWritten() throws Exception {
    try (var history = new ClientHistory(directory)) {
      history.record("/config api-key super-secret");
      history.record("Authorization: Bearer abc");
      history.record("/history off");
      history.enabled(false);
      history.record("private message");
      assertEquals(0, Files.size(history.file()));
    }
  }

  /** 完了済みの履歴を復元することを確認する。 */
  @Test
  void restoresCompletedHistoryWithoutReadingAnActiveClientsFile() throws Exception {
    try (var first = new ClientHistory(directory)) {
      first.record("completed");
    }
    try (var next = new ClientHistory(directory)) {
      assertEquals(java.util.List.of("completed"), next.entries());
      next.record("still-active");
      try (var other = new ClientHistory(directory)) {
        assertEquals(java.util.List.of("completed"), other.entries());
      }
    }
  }

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void rawConfiguredAuthenticationKeyIsExcludedWithoutRelyingOnItsFormat() throws Exception {
    try (var history = new ClientHistory(directory, "opaque-credential-value")) {
      history.record("please use opaque-credential-value");
      assertFalse(history.accepts("opaque-credential-value"));
      assertEquals(0, Files.size(history.file()));
    }
  }
}
