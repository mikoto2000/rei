package dev.mikoto2000.rei.image;

/** 画像プロンプトの内容を補強する。 */
public interface ImagePromptEnhancer {

  /** 画像の生成で用いるenhanceを返す。 */
  String enhance(String userRequest);
}
