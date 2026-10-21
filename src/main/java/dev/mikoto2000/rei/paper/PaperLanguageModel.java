package dev.mikoto2000.rei.paper;

/** 論文の調査と整理に必要な論文文章を生成するモデルを扱う。 */
public interface PaperLanguageModel {
  /** 論文の調査と整理で用いるモデルを返す。 */
  String model();

  /** 指定された条件に従って、依頼された内容を生成する。 */
  String generate(String system, String input, PaperOperation op);
}
