package dev.mikoto2000.rei.paper;

import org.springframework.stereotype.Service;

@Service
public class PaperResearchService {
  public final PaperLibraryService library;
  public final PaperSearchService search;
  public final PaperContentService content;
  public final PaperSummaryService summary;
  public final PaperTranslationService translation;

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
