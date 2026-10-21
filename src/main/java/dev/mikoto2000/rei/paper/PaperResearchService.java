package dev.mikoto2000.rei.paper;

import org.springframework.stereotype.Service;

/** 論文調査のユースケースを実現する。 */
@Service
public class PaperResearchService {
  /** 保管資料。 */
  public final PaperLibraryService library;

  /** 検索。 */
  public final PaperSearchService search;

  /** 内容。 */
  public final PaperContentService content;

  /** 要約。 */
  public final PaperSummaryService summary;

  /** 翻訳。 */
  public final PaperTranslationService translation;

  /** 指定された値や利用先を使って、PaperResearchServiceを初期化する。 */
  public PaperResearchService(
      PaperLibraryService library,
      PaperSearchService search,
      PaperContentService content,
      PaperSummaryService summary,
      PaperTranslationService translation) {
    this.library = library;
    this.search = search;
    this.content = content;
    this.summary = summary;
    this.translation = translation;
  }
}
