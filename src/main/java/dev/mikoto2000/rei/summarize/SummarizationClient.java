package dev.mikoto2000.rei.summarize;

/** Summarizationを外部の接続先へ依頼する窓口。 */
public interface SummarizationClient {

  /** 対象の内容を、要点が分かる形にまとめる。 */
  String summarize(String content);
}
