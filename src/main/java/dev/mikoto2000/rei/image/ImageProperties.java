package dev.mikoto2000.rei.image;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 画像の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@ConfigurationProperties(prefix = "rei.image")
public class ImageProperties {

  /** 出力ディレクトリ。 */
  private Path outputDirectory =
      dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve("images");

  /** 大きさ。 */
  private String size = "1024x1024";

  /** 応答形式。 */
  private String responseFormat = "auto";

  /** タイムアウトの秒数。 */
  private int timeoutSeconds = 300;

  /** プロンプトEnhancement有効状態。 */
  private boolean promptEnhancementEnabled = true;

  /** 画像の生成で参照する出力ディレクトリを返す。 */
  public Path getOutputDirectory() {
    return outputDirectory;
  }

  /** 画像の生成で使用する出力ディレクトリを設定する。 */
  public void setOutputDirectory(Path outputDirectory) {
    this.outputDirectory = outputDirectory;
  }

  /** 画像の生成で参照する大きさを返す。 */
  public String getSize() {
    return size;
  }

  /** 画像の生成で使用する大きさを設定する。 */
  public void setSize(String size) {
    this.size = size;
  }

  /** 画像の生成で参照する応答形式を返す。 */
  public String getResponseFormat() {
    return responseFormat;
  }

  /** 画像の生成で使用する応答形式を設定する。 */
  public void setResponseFormat(String responseFormat) {
    this.responseFormat = responseFormat;
  }

  /** 画像の生成で参照するタイムアウトの秒数を返す。 */
  public int getTimeoutSeconds() {
    return timeoutSeconds;
  }

  /** 画像の生成で使用するタイムアウトの秒数を設定する。 */
  public void setTimeoutSeconds(int timeoutSeconds) {
    this.timeoutSeconds = timeoutSeconds;
  }

  /** 画像の生成において、プロンプトEnhancement有効状態かどうかを判定する。 */
  public boolean isPromptEnhancementEnabled() {
    return promptEnhancementEnabled;
  }

  /** 画像の生成で使用するプロンプトEnhancement有効状態を設定する。 */
  public void setPromptEnhancementEnabled(boolean promptEnhancementEnabled) {
    this.promptEnhancementEnabled = promptEnhancementEnabled;
  }
}
