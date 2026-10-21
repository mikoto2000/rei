package dev.mikoto2000.rei.application.input;

/**
 * 会話入力の受付における入力取得元の選択肢。
 *
 * <p>Input modality; deliberately separate from the Run's Shell/Web authority.
 */
public enum InputSource {
  /** キーボードからの入力。 */
  KEYBOARD,
  /** 音声。 */
  VOICE
}
