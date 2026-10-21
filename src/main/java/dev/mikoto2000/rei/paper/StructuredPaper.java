package dev.mikoto2000.rei.paper;

import java.util.List;

/**
 * 論文の調査と整理で受け渡す項目に整理した内容論文を表す。
 *
 * @param title タイトル
 * @param availability 利用可否
 * @param sections 節一覧
 * @param references 参照先一覧
 * @param warnings 注意事項
 */
public record StructuredPaper(
    String title,
    Availability availability,
    List<PaperSection> sections,
    List<String> references,
    List<String> warnings) {
  /** 論文の調査と整理における利用可否の選択肢。 */
  public enum Availability {
    /** 全体テキスト。 */
    FULL_TEXT,
    /** 概要限定。 */
    ABSTRACT_ONLY,
    /** 付随情報限定。 */
    METADATA_ONLY
  }

  /** 受け取った情報からStructuredPaperを作成する。 */
  public StructuredPaper {
    sections = List.copyOf(sections);
    references = List.copyOf(references);
    warnings = List.copyOf(warnings);
  }
}
