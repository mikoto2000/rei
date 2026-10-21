package dev.mikoto2000.rei.image;

/** 画像生成を外部の接続先へ依頼する窓口。 */
public interface ImageGenerationClient {

  /** 指定された条件に従って、依頼された内容を生成する。 */
  String generate(ImageGenerationRequest request);
}
