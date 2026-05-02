package dev.mikoto2000.rei.sound;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SoundNotificationServiceTest {

  private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
  private final PrintStream originalOut = System.out;

  @BeforeEach
  void setUpStreams() {
    System.setOut(new PrintStream(outContent));
  }

  @AfterEach
  void restoreStreams() {
    System.setOut(originalOut);
  }

  // --- enabled=false のフォールバック ---

  @Test
  void notifyFallsBackToConsoleWhenDisabled() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(false);
    SoundNotificationService service = new SoundNotificationService(props);

    service.notify("テスト通知");

    assertOutputContains("テスト通知");
  }

  // --- command 未設定のフォールバック ---

  @Test
  void notifyFallsBackToConsoleWhenCommandIsEmpty() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    // command はデフォルトで空リスト
    SoundNotificationService service = new SoundNotificationService(props);

    service.notify("コマンドなし通知");

    assertOutputContains("コマンドなし通知");
  }

  // --- {{MESSAGE}} 置換 ---

  @Test
  void notifyReplacesMessagePlaceholderInCommand() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("echo", "{{MESSAGE}}"));

    List<List<String>> capturedCommands = new ArrayList<>();
    SoundNotificationService service = new SoundNotificationService(props) {
      @Override
      protected ProcessBuilder createProcessBuilder(List<String> command) {
        capturedCommands.add(new ArrayList<>(command));
        // 実際には実行しない: exit 0 相当のダミープロセスを返す
        return new ProcessBuilder("cmd", "/c", "exit", "0");
      }
    };

    service.notify("hello world");

    assertFalse(capturedCommands.isEmpty(), "コマンドが実行されるべき");
    assertTrue(capturedCommands.getFirst().contains("hello world"),
        "{{MESSAGE}} が 'hello world' に置換されるべき");
    assertFalse(capturedCommands.getFirst().contains("{{MESSAGE}}"),
        "{{MESSAGE}} プレースホルダーが残っていないべき");
  }

  @Test
  void notifyLogsWarnWhenNoMessagePlaceholder() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("echo", "no-placeholder"));

    SoundNotificationService service = new SoundNotificationService(props) {
      @Override
      protected ProcessBuilder createProcessBuilder(List<String> command) {
        return new ProcessBuilder("cmd", "/c", "exit", "0");
      }
    };

    // {{MESSAGE}} がなくても例外なく実行されること（warn ログは出力される）
    service.notify("テスト");
    // warn ログの検証はログフレームワークのモックが必要なため、
    // ここでは例外が発生しないことのみ検証する
  }

  // --- 外部プログラム実行（正常系）---

  @Test
  void notifyExecutesExternalProgramWhenEnabledAndCommandSet() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("echo", "{{MESSAGE}}"));

    List<List<String>> capturedCommands = new ArrayList<>();
    SoundNotificationService service = new SoundNotificationService(props) {
      @Override
      protected ProcessBuilder createProcessBuilder(List<String> command) {
        capturedCommands.add(new ArrayList<>(command));
        return new ProcessBuilder("cmd", "/c", "exit", "0");
      }
    };

    service.notify("正常系テスト");

    assertFalse(capturedCommands.isEmpty(), "外部プログラムが実行されるべき");
    // 標準出力フォールバックは呼ばれないこと
    assertFalse(outContent.toString().contains("[通知]"),
        "正常系では標準出力フォールバックが呼ばれないべき");
  }

  // --- タイムアウト処理 ---

  @Test
  void notifyFallsBackToConsoleOnTimeout() throws Exception {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("sleep", "{{MESSAGE}}"));

    SoundNotificationService service = new SoundNotificationService(props) {
      @Override
      protected ProcessBuilder createProcessBuilder(List<String> command) {
        // 実際には終了しないプロセスをシミュレート: waitFor が false を返すモック
        return new ProcessBuilder("cmd", "/c", "ping", "-n", "100", "127.0.0.1");
      }

      @Override
      protected long getTimeoutSeconds() {
        return 1L; // テスト用に 1 秒に短縮
      }
    };

    service.notify("タイムアウトテスト");

    assertOutputContains("タイムアウトテスト");
  }

  // --- 外部プログラム失敗時のフォールバック ---

  @Test
  void notifyFallsBackToConsoleWhenExternalProgramFails() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("echo", "{{MESSAGE}}"));

    SoundNotificationService service = new SoundNotificationService(props) {
      @Override
      protected ProcessBuilder createProcessBuilder(List<String> command) {
        // exit 1 で失敗するプロセスを返す
        return new ProcessBuilder("cmd", "/c", "exit", "1");
      }
    };

    service.notify("失敗テスト");

    assertOutputContains("失敗テスト");
  }

  // --- テストヘルパー ---

  private void assertOutputContains(String expected) {
    assertTrue(outContent.toString().contains(expected),
        "標準出力に「" + expected + "」が含まれるべき。実際の出力: " + outContent);
  }
}
