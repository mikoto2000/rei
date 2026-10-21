package dev.mikoto2000.rei.vectordocument;

/**
 * 検索対象文書の管理で受け渡すベクトル文書検索結果を表す。
 *
 * @param docId 文書の識別子
 * @param source 取得元
 * @param chunkIndex 文書の分割単位位置
 * @param score 評価値
 * @param snippet 抜粋
 */
public record VectorDocumentSearchResult(
    String docId, String source, int chunkIndex, Double score, String snippet) {}
