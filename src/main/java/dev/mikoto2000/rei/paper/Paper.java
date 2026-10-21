package dev.mikoto2000.rei.paper;

import java.util.List;

/**
 * 論文の調査と整理で受け渡す論文を表す。
 *
 * @param id 識別子
 * @param title タイトル
 * @param authors 著者一覧
 * @param abstractText 概要テキスト
 * @param publicationYear 公開情報年
 * @param venue 発表先
 * @param doi 論文のDOI
 * @param arxivId arXiv識別子
 * @param openAlexId 開始Alex識別子
 * @param citationCount citation件数
 * @param openAccess 開始アクセス
 * @param landingPageUrl landingページURL
 * @param pdfUrl PDFURL
 * @param tags タグ一覧
 * @param publishedAt 公開日時
 * @param licenses ライセンス情報
 */
public record Paper(
    String id,
    String title,
    List<String> authors,
    String abstractText,
    Integer publicationYear,
    String venue,
    String doi,
    String arxivId,
    String openAlexId,
    Long citationCount,
    Boolean openAccess,
    String landingPageUrl,
    String pdfUrl,
    List<String> tags,
    String publishedAt,
    List<String> licenses) {
  /** 受け取った情報からPaperを作成する。 */
  public Paper {
    authors = authors == null ? List.of() : List.copyOf(authors);
    tags = tags == null ? List.of() : List.copyOf(tags);
    licenses = licenses == null ? List.of() : List.copyOf(licenses);
  }

  /** 論文の調査と整理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public Paper(
      String id,
      String title,
      List<String> authors,
      String abstractText,
      Integer publicationYear,
      String venue,
      String doi,
      String arxivId,
      String openAlexId,
      Long citationCount,
      Boolean openAccess,
      String landingPageUrl,
      String pdfUrl,
      List<String> tags) {
    this(
        id,
        title,
        authors,
        abstractText,
        publicationYear,
        venue,
        doi,
        arxivId,
        openAlexId,
        citationCount,
        openAccess,
        landingPageUrl,
        pdfUrl,
        tags,
        null,
        List.of());
  }

  /** 論文の調査と整理で用いる公開情報を返す。 */
  public Paper publication(String date, List<String> license) {
    return new Paper(
        id,
        title,
        authors,
        abstractText,
        publicationYear,
        venue,
        doi,
        arxivId,
        openAlexId,
        citationCount,
        openAccess,
        landingPageUrl,
        pdfUrl,
        tags,
        date,
        license);
  }

  /** 指定された識別子を反映した情報を作成する。 */
  public Paper withId(String value) {
    return new Paper(
        value,
        title,
        authors,
        abstractText,
        publicationYear,
        venue,
        doi,
        arxivId,
        openAlexId,
        citationCount,
        openAccess,
        landingPageUrl,
        pdfUrl,
        tags,
        publishedAt,
        licenses);
  }
}
