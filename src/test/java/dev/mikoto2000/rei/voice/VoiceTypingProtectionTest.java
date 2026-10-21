package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.ui.shell.JLineShellEventOutput;
import dev.mikoto2000.rei.ui.shell.UserInputFrame;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 音声Typing保護の結果と状態の契約を検証するテスト。 */
@Timeout(15)
class VoiceTypingProtectionTest {
  /** 検証用に、必要な状態が整ってから先へ進むため、音声Typing保護検証を待つ。 */
  static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    assertThat(condition.getAsBoolean()).isTrue();
  }

  /** 音声による対話で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void actualJLineKeepsPartiallyTypedJapaneseDuringVoiceEventsAndStreamingOutput()
      throws Exception {
    var keyboard = new PipedOutputStream();
    var input = new PipedInputStream(keyboard, 8192);
    var display = new ByteArrayOutputStream();
    var worker =
        Executors.newSingleThreadExecutor(
            r -> {
              var t = new Thread(r, "typing-fixture");
              t.setDaemon(true);
              return t;
            });
    try (var terminal =
        new DumbTerminal("voice-fixture", "xterm", input, display, StandardCharsets.UTF_8)) {
      terminal.setSize(new Size(80, 24));
      var reader = LineReaderBuilder.builder().terminal(terminal).build();
      var result = worker.submit(() -> reader.readLine("rei> "));
      await(reader::isReading);
      keyboard.write("日本語の入力途中".getBytes(StandardCharsets.UTF_8));
      keyboard.flush();
      await(() -> reader.getBuffer().toString().equals("日本語の入力途中"));
      var output = new JLineShellEventOutput(reader);
      var voice = new VoiceShellEventRenderer(output);
      voice.accept(
          new VoiceEventPublisher.Event(VoiceEventPublisher.Type.STATE_CHANGED, "LISTENING"));
      voice.accept(
          new VoiceEventPublisher.Event(
              VoiceEventPublisher.Type.SEGMENT_QUEUE_FULL, "private transcript must not appear"));
      output.print("エージェントの");
      output.print("応答\n");
      output.flush();
      output.print("回答の途中");
      output.printBlock(
          UserInputFrame.format(
              "音声入力のテスト\n" + "長い音声入力".repeat(20), java.time.LocalTime.of(1, 37, 16)));
      output.print("回答の続き\n");
      output.flush();
      voice.accept(
          new VoiceEventPublisher.Event(VoiceEventPublisher.Type.DEVICE_CHANGED, "private detail"));
      assertThat(reader.getBuffer().toString()).isEqualTo("日本語の入力途中");
      keyboard.write("を続けます\n".getBytes(StandardCharsets.UTF_8));
      keyboard.flush();
      assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("日本語の入力途中を続けます");
      assertThat(display.toString(StandardCharsets.UTF_8))
          .contains("受付中", "エージェントの応答", "User (01:37:16)", "音声入力のテスト", "長い音声入力", "回答の途中", "回答の続き")
          .doesNotContain("private transcript", "private detail");
    } finally {
      keyboard.close();
      worker.shutdownNow();
    }
  }
}
