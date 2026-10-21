package dev.mikoto2000.rei.search;

import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import java.util.List;

/**
 * 調査情報の整理で受け渡す検索知識結果を表す。
 *
 * @param query 検索条件
 * @param vectorResults ベクトル結果一覧
 * @param webContext Web文脈
 * @param webSearchSkippedMessage Web検索対象外メッセージ
 */
public record SearchKnowledgeResult(
    String query,
    List<VectorDocumentSearchResult> vectorResults,
    WebSearchContext webContext,
    String webSearchSkippedMessage) {}
