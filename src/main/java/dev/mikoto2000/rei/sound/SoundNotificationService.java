package dev.mikoto2000.rei.sound;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SoundNotificationService {

  private static final Logger log = LoggerFactory.getLogger(SoundNotificationService.class);
  private static final String MESSAGE_PLACEHOLDER = "{{MESSAGE}}";
  private static final long TIMEOUT_SECONDS = 300L;

  private final SoundNotificationProperties properties;

  /**
   * 通知メッセージを使って音声通知を実行する。
   * 失敗時は標準出力にフォールバックする。
   *
   * @param message 通知メッセージ
   */
  public void notify(String message) {
    if (!properties.isEnabled()) {
      log.warn("音声通知が無効化されています。標準出力にフォールバックします。");
      fallbackToConsole(message);
      return;
    }

    if (properties.getCommand().isEmpty()) {
      log.warn("音声通知コマンドが設定されていません。標準出力にフォールバックします。");
      fallbackToConsole(message);
      return;
    }

    List<String> resolvedCommand = resolveCommand(message);
    executeCommand(resolvedCommand, message);
  }

  /**
   * コマンドリストの {{MESSAGE}} をメッセージに置換する。
   * {{MESSAGE}} が含まれない場合は warn ログを出力してそのまま返す。
   */
  private List<String> resolveCommand(String message) {
    boolean hasPlaceholder = properties.getCommand().stream()
        .anyMatch(arg -> arg.contains(MESSAGE_PLACEHOLDER));

    if (!hasPlaceholder) {
      log.warn("コマンドに {} が含まれていません。メッセージは渡されません。", MESSAGE_PLACEHOLDER);
    }

    return properties.getCommand().stream()
        .map(arg -> arg.replace(MESSAGE_PLACEHOLDER, message))
        .toList();
  }

  /**
   * 外部プログラムを実行する。失敗・タイムアウト時は標準出力にフォールバックする。
   */
  private void executeCommand(List<String> command, String message) {
    Process process = null;
    try {
      process = createProcessBuilder(command).start();
      boolean finished = process.waitFor(getTimeoutSeconds(), TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        log.warn("音声通知コマンドがタイムアウトしました（{}秒）。標準出力にフォールバックします。", getTimeoutSeconds());
        fallbackToConsole(message);
        return;
      }
      int exitCode = process.exitValue();
      if (exitCode != 0) {
        log.warn("音声通知コマンドが非ゼロ終了コード {} で終了しました。標準出力にフォールバックします。", exitCode);
        fallbackToConsole(message);
      }
    } catch (Exception e) {
      log.warn("音声通知コマンドの実行に失敗しました: {}。標準出力にフォールバックします。", e.getMessage());
      if (process != null) {
        process.destroyForcibly();
      }
      fallbackToConsole(message);
    }
  }

  /**
   * ProcessBuilder を生成する。テストでオーバーライド可能。
   */
  protected ProcessBuilder createProcessBuilder(List<String> command) {
    return new ProcessBuilder(command);
  }

  /**
   * タイムアウト秒数を返す。テストでオーバーライド可能。
   */
  protected long getTimeoutSeconds() {
    return TIMEOUT_SECONDS;
  }

  /** 標準出力フォールバック */
  private void fallbackToConsole(String message) {
    System.out.println("[通知] " + message);
  }
}
