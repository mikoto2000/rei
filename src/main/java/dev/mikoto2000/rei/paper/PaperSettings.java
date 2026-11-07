package dev.mikoto2000.rei.paper;

/** 一回の論文処理で使用する、不変な設定値。 */
record PaperSettings(
    PaperSummary.Mode defaultSummaryMode,
    PaperTranslation.Mode defaultTranslationMode,
    boolean enabled,
    int maxPdfBytes,
    int maxPages,
    int maxExtractedChars,
    int maxSections,
    int maxSectionChars,
    int translationChunkSize,
    int summaryInputLimit,
    int maxLibrarySearchResults,
    int defaultLimit,
    String promptVersion,
    String glossaryVersion) {
  /** 設定元を変更する権限を持たずに、現在の値を取得する。 */
  static PaperSettings capture(PaperProperties source) {
    return new PaperSettings(
        source.getDefaultSummaryMode(),
        source.getDefaultTranslationMode(),
        source.isEnabled(),
        source.getMaxPdfBytes(),
        source.getMaxPages(),
        source.getMaxExtractedChars(),
        source.getMaxSections(),
        source.getMaxSectionChars(),
        source.getTranslationChunkSize(),
        source.getSummaryInputLimit(),
        source.getMaxLibrarySearchResults(),
        source.getDefaultLimit(),
        source.getPromptVersion(),
        source.getGlossaryVersion());
  }
}
