package dev.mikoto2000.rei.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * 音声による対話に必要なSapi音声出力を扱う。
 *
 * <p>Windows-installed SAPI voice; no speech service, SSML, or audio file output.
 */
public final class SapiVoiceOutput implements VoiceSpeechOutput {
  /** バックエンドの利用可否を確認し、軽量CLIを起動して終了まで管理する。 */
  @FunctionalInterface
  interface Launcher {
    /** 要求された処理を開始する。 */
    Process start(List<String> command) throws IOException;
  }

  /** 起動。 */
  private final Launcher launcher;

  /** タイムアウト。 */
  private final java.time.Duration timeout;

  /** 活動中。 */
  private final AtomicReference<Process> active = new AtomicReference<>();

  /** スクリプト。 */
  private static final String SCRIPT =
      """
      $ErrorActionPreference='Stop'
      [Console]::InputEncoding=[Text.UTF8Encoding]::new($false)
      $request=[Console]::In.ReadToEnd() | ConvertFrom-Json
      $speaker=New-Object -ComObject SAPI.SpVoice
      try {
        $found=$false
        foreach($token in $speaker.GetVoices()) {
          if($token.GetDescription() -ceq [string]$request.voice) {
            $speaker.Voice=$token; $found=$true; break
          }
        }
        if(-not $found) { throw 'Selected installed voice unavailable' }
        [void]$speaker.Speak([string]$request.text,16)
      } finally { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($speaker) }
      """;

  /** 音声による対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SapiVoiceOutput() {
    this(
        args -> {
          if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"))
            throw new IOException("Windows SAPI required");
          return new ProcessBuilder(args)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
        });
  }

  /** 音声による対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  SapiVoiceOutput(Launcher launcher) {
    this(launcher, java.time.Duration.ofMinutes(3));
  }

  /** 指定された値や利用先を使って、SapiVoiceOutputを初期化する。 */
  SapiVoiceOutput(Launcher launcher, java.time.Duration timeout) {
    this.launcher = Objects.requireNonNull(launcher);
    this.timeout = Objects.requireNonNull(timeout);
    if (timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(java.time.Duration.ofMinutes(3)) > 0)
      throw new IllegalArgumentException("Invalid local speech timeout");
  }

  /** 音声による対話に関する読み上げを処理する。 */
  @Override
  public void speak(String voice, String text, BooleanSupplier current) throws Exception {
    if (voice == null
        || voice.isBlank()
        || voice.length() > 256
        || text == null
        || text.length() > 16384)
      throw new IllegalArgumentException("Invalid local speech request");
    if (!current.getAsBoolean()) return;
    String windows = System.getenv().getOrDefault("SystemRoot", "C:/Windows");
    var command =
        List.of(
            Path.of(windows, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
            "-NoProfile",
            "-NonInteractive",
            "-WindowStyle",
            "Hidden",
            "-EncodedCommand",
            Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE)));
    var process = launcher.start(command);
    if (!active.compareAndSet(null, process)) {
      terminate(process);
      throw new IllegalStateException("Local playback already active");
    }
    var watchdog =
        new Thread(
            () -> {
              try {
                TimeUnit.NANOSECONDS.sleep(timeout.toNanos());
                if (active.get() == process) process.destroyForcibly();
              } catch (InterruptedException cancelled) {
                /* Normal completion or explicit stop. */
              }
            },
            "voice-playback-deadline");
    watchdog.setDaemon(true);
    try {
      watchdog.start();
      try (var stdin = process.getOutputStream()) {
        stdin.write(new ObjectMapper().writeValueAsBytes(Map.of("voice", voice, "text", text)));
      }
      long deadline = System.nanoTime() + timeout.toNanos();
      while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        if (!current.getAsBoolean()) return;
        if (System.nanoTime() - deadline >= 0) throw new IOException("Local playback timed out");
      }
      if (!current.getAsBoolean()) return;
      if (process.exitValue() != 0) throw new IOException("Local playback failed");
    } finally {
      watchdog.interrupt();
      terminate(process);
      active.compareAndSet(process, null);
    }
  }

  /** 音声による対話に関する終了を処理する。 */
  private static void terminate(Process process) {
    if (!process.isAlive()) return;
    process.destroyForcibly();
    try {
      if (!process.waitFor(3000, TimeUnit.MILLISECONDS))
        throw new IllegalStateException("Local playback did not terminate");
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
    }
  }

  /** 実行中の処理を停止する。 */
  @Override
  public void stop() {
    var process = active.get();
    if (process != null) process.destroyForcibly();
  }
}
