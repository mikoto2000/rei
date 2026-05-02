package dev.mikoto2000.rei.sound;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SoundNotificationTools {

  private final SoundNotificationService soundNotificationService;

  /**
   * AI エージェントから音声通知を実行するツール。
   * ユーザーへの重要な通知を音声で伝えたいときに使用する。
   *
   * @param message 通知メッセージ
   * @return 通知の成否を示す文字列
   */
  @Tool(name = "soundNotify",
      description = """
          音声通知を実行します。
          ユーザーへの重要なお知らせ・リマインダー・アラートを音声で伝えたいときに使用します。
          外部の音声合成プログラムにメッセージを渡して実行します。
          音声通知が無効または失敗した場合は、標準出力に通知します。
          """)
  public String notify(String message) {
    try {
      soundNotificationService.notify(message);
      return "通知しました: " + message;
    } catch (Exception e) {
      return "通知に失敗しました: " + e.getMessage();
    }
  }
}
