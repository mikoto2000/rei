package dev.mikoto2000.rei.paper;

/**
 * 論文の調査と整理で受け渡す論文根拠を表す。
 *
 * @param claim 利用権の確保
 * @param section 節
 * @param page ページ
 * @param text テキスト
 */
public record PaperEvidence(String claim, String section, int page, String text) {}
