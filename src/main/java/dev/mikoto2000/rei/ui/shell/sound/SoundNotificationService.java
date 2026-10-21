package dev.mikoto2000.rei.ui.shell.sound;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 音通知通知のユースケースを実現する。 */
@Service
@RequiredArgsConstructor
public class SoundNotificationService {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(SoundNotificationService.class);

  /** メッセージPLACEHOLDER。 */
  private static final String MESSAGE_PLACEHOLDER = "{{MESSAGE}}";

  /** タイムアウトの秒数。 */
  private static final long TIMEOUT_SECONDS = 300L;

  /** 通知排他制御。 */
  private final ReentrantLock notificationLock = new ReentrantLock(true);

  /** この機能の動作設定。 */
  private final SoundNotificationProperties properties;

  /** 音声音声実行可否の判断。 */
  private dev.mikoto2000.rei.voice.VoiceAudioGate voiceAudioGate;

  /** ターミナルの音通知で使用する音声音声実行可否の判断を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setVoiceAudioGate(dev.mikoto2000.rei.voice.VoiceAudioGate gate) {
    this.voiceAudioGate = gate;
  }

  /** 状態の変化を利用先へ伝えるため、音通知通知サービスを通知する。 */
  public void notify(String message) {
    notificationLock.lock();
    try {
      if (!properties.isEnabled()) {
        log.warn("Sound notification is disabled. Falling back to console output.");
        fallbackToConsole(message);
        return;
      }

      if (properties.getCommand().isEmpty()) {
        log.warn("Sound notification command is not configured. Falling back to console output.");
        fallbackToConsole(message);
        return;
      }

      List<String> resolvedCommand = resolveCommand(message);
      executeCommand(resolvedCommand, message);
    } finally {
      notificationLock.unlock();
    }
  }

  /** 指定や設定に応じた対象を利用するため、コマンドを確定する。 */
  private List<String> resolveCommand(String message) {
    boolean hasPlaceholder =
        properties.getCommand().stream().anyMatch(arg -> arg.contains(MESSAGE_PLACEHOLDER));

    if (!hasPlaceholder) {
      log.warn(
          "Command does not contain {} placeholder; message cannot be passed as an argument.",
          MESSAGE_PLACEHOLDER);
    }

    return properties.getCommand().stream()
        .map(arg -> arg.replace(MESSAGE_PLACEHOLDER, message))
        .toList();
  }

  /** 依頼された操作を進めるため、コマンドを実行する。 */
  private void executeCommand(List<String> command, String message) {
    Process process = null;
    var gate = voiceAudioGate;
    try (var playback =
        gate == null
            ? (dev.mikoto2000.rei.voice.VoiceAudioGate.Playback) () -> {}
            : gate.playback(java.time.Duration.ofMillis(800))) {
      process = createProcessBuilder(command).start();
      boolean finished = process.waitFor(getTimeoutSeconds(), TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        log.warn(
            "Sound notification command timed out after {} seconds. Falling back to console output.",
            getTimeoutSeconds());
        fallbackToConsole(message);
        return;
      }
      int exitCode = process.exitValue();
      if (exitCode != 0) {
        log.warn(
            "Sound notification command failed with exit code {}. Falling back to console output.",
            exitCode);
        fallbackToConsole(message);
      }
    } catch (Exception e) {
      log.warn(
          "Sound notification command failed: {}. Falling back to console output.", e.getMessage());
      if (process != null) {
        process.destroyForcibly();
      }
      fallbackToConsole(message);
    }
  }

  /** 新たな対象を利用できるよう、プロセス組み立てを作成する。 */
  protected ProcessBuilder createProcessBuilder(List<String> command) {
    return new ProcessBuilder(command);
  }

  /** ターミナルの音通知で参照するタイムアウトの秒数を返す。 */
  protected long getTimeoutSeconds() {
    return TIMEOUT_SECONDS;
  }

  /** ターミナルの音通知に関する代替手段終点コンソールを処理する。 */
  private void fallbackToConsole(String message) {
    System.out.println("[notification] " + message);
  }
}
