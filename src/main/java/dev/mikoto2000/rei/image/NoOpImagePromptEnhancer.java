package dev.mikoto2000.rei.image;

/** No操作の種別画像プロンプトの内容を補強する。 */
class NoOpImagePromptEnhancer implements ImagePromptEnhancer {

  /** 画像の生成で用いるenhanceを返す。 */
  @Override
  public String enhance(String userRequest) {
    return userRequest;
  }
}
