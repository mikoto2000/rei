package dev.mikoto2000.rei.image;

import java.nio.file.Path;

/**
 * 画像の生成で受け渡す画像生成結果を表す。
 *
 * @param success 成功
 * @param savedPath 保存済みパス
 * @param message メッセージ
 * @param prompt プロンプト
 * @param artifactId 成果物識別子
 */
public record ImageGenerationResult(
    boolean success, Path savedPath, String message, String prompt, String artifactId) {
  /** 画像の生成を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ImageGenerationResult(boolean success, Path savedPath, String message, String prompt) {
    this(success, savedPath, message, prompt, null);
  }

  /** 処理が成功したことを、呼び出し元へ伝える情報として表す。 */
  public static ImageGenerationResult success(Path savedPath, String prompt, String artifactId) {
    return new ImageGenerationResult(true, savedPath, null, prompt, artifactId);
  }

  /** 処理が成功したことを、呼び出し元へ伝える情報として表す。 */
  public static ImageGenerationResult success(Path savedPath) {
    return success(savedPath, null);
  }

  /** 処理が成功したことを、呼び出し元へ伝える情報として表す。 */
  public static ImageGenerationResult success(Path savedPath, String prompt) {
    return new ImageGenerationResult(true, savedPath, null, prompt);
  }

  /** 処理を継続できなかった理由を、失敗情報として表す。 */
  public static ImageGenerationResult failure(String message) {
    return new ImageGenerationResult(false, null, message, null);
  }
}
