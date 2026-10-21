package dev.mikoto2000.rei.summarize;

/** Webページ内容に必要な情報を入力から取り出す。 */
public interface WebPageContentExtractor {

  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
  String extract(String url, String html);
}
