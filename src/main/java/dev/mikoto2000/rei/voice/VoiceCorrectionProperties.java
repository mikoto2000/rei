package dev.mikoto2000.rei.voice;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 音声補正の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rei.voice.correction")
public class VoiceCorrectionProperties {
  /** 有効状態。 */
  private boolean enabled;

  /** タイムアウトミリ秒。 */
  private int timeoutMs = 5000;

  /** 最大入力文字数。 */
  private int maxInputChars = 500;

  /** 最大文脈文字数。 */
  private int maxContextChars = 1000;

  /** 最大出力トークン数。 */
  private int maxOutputTokens = 512;

  /** 最大並行要求一覧。 */
  private int maxConcurrentRequests = 1;

  /** 最大待機中要求一覧。 */
  private int maxQueuedRequests = 3;

  /** 最大対応表項目一覧。 */
  private int maxDictionaryEntries = 64;

  /** 最大使用トークン数の合計。 */
  private long maxTotalTokens = 4096;

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public void validate() {
    if (timeoutMs < 1
        || timeoutMs > 60000
        || maxInputChars < 1
        || maxInputChars > 2000
        || maxContextChars < 0
        || maxContextChars > 4000
        || maxOutputTokens < 1
        || maxOutputTokens > 4096
        || maxConcurrentRequests < 1
        || maxConcurrentRequests > 4
        || maxQueuedRequests < 1
        || maxQueuedRequests > 16
        || maxDictionaryEntries < 0
        || maxDictionaryEntries > 128
        || maxTotalTokens < 0)
      throw new IllegalArgumentException("Invalid voice correction limits");
  }
}
