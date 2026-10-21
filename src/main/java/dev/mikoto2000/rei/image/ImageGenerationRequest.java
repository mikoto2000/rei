package dev.mikoto2000.rei.image;

import java.nio.file.Path;

/**
 * 画像の生成で受け渡す画像生成要求を表す。
 *
 * @param prompt プロンプト
 * @param outputPath 出力パス
 * @param model モデル
 * @param size 大きさ
 * @param enhancePrompt enhanceプロンプト
 */
public record ImageGenerationRequest(
    String prompt, Path outputPath, String model, ImageSize size, boolean enhancePrompt) {

  /** 画像の生成を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ImageGenerationRequest(String prompt, Path outputPath, String model, ImageSize size) {
    this(prompt, outputPath, model, size, true);
  }
}
