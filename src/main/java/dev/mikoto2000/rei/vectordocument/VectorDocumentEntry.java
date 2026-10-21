package dev.mikoto2000.rei.vectordocument;

/**
 * 検索対象文書の管理で受け渡すベクトル文書項目を表す。
 *
 * @param docId 文書の識別子
 * @param source 取得元
 * @param chunkCount 文書の分割単位件数
 * @param ingestedAt ingested日時
 */
public record VectorDocumentEntry(String docId, String source, int chunkCount, String ingestedAt) {}
