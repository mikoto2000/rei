package dev.mikoto2000.rei.paper;

/**
 * 論文の調査と整理で受け渡す論文節を表す。
 *
 * @param heading 見出し
 * @param text テキスト
 * @param startPage 開始ページ
 * @param endPage 終了ページ
 */
public record PaperSection(String heading, String text, int startPage, int endPage) {}
