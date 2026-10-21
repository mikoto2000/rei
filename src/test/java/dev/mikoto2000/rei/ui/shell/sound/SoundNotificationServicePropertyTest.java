package dev.mikoto2000.rei.ui.shell.sound;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SoundNotificationService のプロパティテスト。 jqwik は JUnit 6 と互換性がないため、@ParameterizedTest + @MethodSource
 * で代替する。
 */
@org.junit.jupiter.api.Tag("e2e")
class SoundNotificationServicePropertyTest {

  /** 検証に使う出力先内容。 */
  private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();

  /** 検証に使う元の出力先。 */
  private final PrintStream originalOut = System.out;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setUpStreams() {
    System.setOut(new PrintStream(outContent));
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void restoreStreams() {
    System.setOut(originalOut);
    outContent.reset();
  }

  // --- Property 1: enabled=false のとき標準出力にフォールバックする ---

  /** {@code messageVariants} の条件を再現するための補助情報を返す。 */
  static Stream<String> messageVariants() {
    return Stream.of(
        "hello",
        "",
        "日本語メッセージ",
        "メッセージ with spaces",
        "special!@#$%^&*()",
        "改行\nを含む",
        "タブ\tを含む",
        "a".repeat(1000),
        "🎵音声通知🎵",
        "  前後にスペース  ");
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @ParameterizedTest(name = "message={0}")
  @MethodSource("messageVariants")
  @Tag("sound-notification-property-1-enabledFalse")
  void enabledFalseAlwaysFallsBackToConsole(String message) {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(false);
    SoundNotificationService service = new SoundNotificationService(props);

    service.notify(message);

    assertTrue(
        outContent.toString().contains(message),
        "enabled=false のとき標準出力にメッセージが出力されるべき。message=" + message);
    outContent.reset();
  }

  // --- Property 2: command が空のとき標準出力にフォールバックする ---

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @ParameterizedTest(name = "message={0}")
  @MethodSource("messageVariants")
  @Tag("sound-notification-property-2-emptyCommand")
  void emptyCommandAlwaysFallsBackToConsole(String message) {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    // command はデフォルトで空リスト
    SoundNotificationService service = new SoundNotificationService(props);

    service.notify(message);

    assertTrue(
        outContent.toString().contains(message),
        "command が空のとき標準出力にメッセージが出力されるべき。message=" + message);
    outContent.reset();
  }

  // --- Property 3: {{MESSAGE}} プレースホルダーが正しく置換される ---

  /** {@code messagePlaceholderVariants} の条件を再現するための補助情報を返す。 */
  static Stream<String> messagePlaceholderVariants() {
    return Stream.of(
        "hello",
        "日本語",
        "with spaces",
        "special!@#",
        "改行なし",
        "short",
        "a".repeat(100),
        "🎵emoji🎵",
        "tab\there",
        "quote\"here");
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @ParameterizedTest(name = "message={0}")
  @MethodSource("messagePlaceholderVariants")
  @Tag("sound-notification-property-3-messagePlaceholder")
  void messageIsCorrectlySubstitutedInCommand(String message) {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("echo", "{{MESSAGE}}", "--flag"));

    List<List<String>> capturedCommands = new ArrayList<>();
    SoundNotificationService service =
        new SoundNotificationService(props) {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          @Override
          protected ProcessBuilder createProcessBuilder(List<String> command) {
            capturedCommands.add(new ArrayList<>(command));
            return new ProcessBuilder("cmd", "/c", "exit", "0");
          }
        };

    service.notify(message);

    assertFalse(capturedCommands.isEmpty(), "コマンドが実行されるべき");
    List<String> cmd = capturedCommands.getFirst();
    assertTrue(cmd.contains(message), "メッセージがコマンドに含まれるべき。message=" + message);
    assertFalse(cmd.contains("{{MESSAGE}}"), "{{MESSAGE}} プレースホルダーが残っていないべき");
  }

  // --- Property 4: 外部プログラム実行失敗時に標準出力にフォールバックする ---

  /** ターミナルの音通知で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @ParameterizedTest(name = "message={0}")
  @MethodSource("messageVariants")
  @Tag("sound-notification-property-4-execFailure")
  void execFailureAlwaysFallsBackToConsole(String message) {
    SoundNotificationProperties props = new SoundNotificationProperties();
    props.setEnabled(true);
    props.setCommand(List.of("echo", "{{MESSAGE}}"));

    SoundNotificationService service =
        new SoundNotificationService(props) {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          @Override
          protected ProcessBuilder createProcessBuilder(List<String> command) {
            return new ProcessBuilder("cmd", "/c", "exit", "1");
          }
        };

    service.notify(message);

    assertTrue(
        outContent.toString().contains(message),
        "外部プログラム失敗時に標準出力にメッセージが出力されるべき。message=" + message);
    outContent.reset();
  }
}
