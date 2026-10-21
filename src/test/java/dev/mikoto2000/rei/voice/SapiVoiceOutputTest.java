package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Sapi音声出力の結果と状態の契約を検証するテスト。 */
class SapiVoiceOutputTest {
  /** 音声による対話で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void deadlineAlsoBoundsBlockedStdinWrites() throws Exception {
    var process = mock(Process.class);
    var killed = new java.util.concurrent.CountDownLatch(1);
    when(process.destroyForcibly())
        .thenAnswer(
            call -> {
              killed.countDown();
              return process;
            });
    when(process.getOutputStream())
        .thenReturn(
            new java.io.OutputStream() {
              /** 検証用に、指定された内容を書き込み先へ反映する。 */
              public void write(int b) throws java.io.IOException {
                try {
                  if (!killed.await(2, TimeUnit.SECONDS))
                    throw new AssertionError("deadline failed");
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new java.io.IOException(e);
                }
              }
            });
    when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
    when(process.exitValue()).thenReturn(137);
    var output = new SapiVoiceOutput(args -> process, java.time.Duration.ofMillis(50));
    assertThatThrownBy(() -> output.speak("Haruka", "hello", () -> true))
        .isInstanceOf(java.io.IOException.class);
    verify(process).destroyForcibly();
  }

  /** そのままの値テキストを利用することを確認する。 */
  @Test
  void textTravelsOnlyThroughStdinAndSpeakUsesLiteralText() throws Exception {
    var process = mock(Process.class);
    var stdin = new ByteArrayOutputStream();
    when(process.getOutputStream()).thenReturn(stdin);
    when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
    when(process.exitValue()).thenReturn(0);
    var command = new java.util.ArrayList<String>();
    var output =
        new SapiVoiceOutput(
            args -> {
              command.addAll(args);
              return process;
            });
    String text = "<speak>hello</speak> $(Get-Content secret)";
    output.speak("Haruka", text, () -> true);
    assertThat(command).noneMatch(arg -> arg.contains(text) || arg.contains("Haruka"));
    String script =
        new String(Base64.getDecoder().decode(command.getLast()), StandardCharsets.UTF_16LE);
    assertThat(script)
        .contains("Speak([string]$request.text,16)", "ReadToEnd()", "GetDescription()");
    assertThat(stdin.toString(StandardCharsets.UTF_8)).contains("Haruka", text);
  }

  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationKillsOnlyOwnedProcess() throws Exception {
    var process = mock(Process.class);
    when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
    when(process.isAlive()).thenReturn(true);
    when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false, true);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var output = new SapiVoiceOutput(args -> process);
    output.speak("Haruka", "hello", () -> calls.incrementAndGet() < 2);
    verify(process).destroyForcibly();
  }
}
