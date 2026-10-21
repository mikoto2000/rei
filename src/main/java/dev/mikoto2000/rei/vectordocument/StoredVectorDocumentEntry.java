package dev.mikoto2000.rei.vectordocument;

import java.util.List;

/**
 * 検索対象文書の管理で受け渡す保存済みベクトル文書項目を表す。
 *
 * @param docId 文書の識別子
 * @param source 取得元
 * @param chunkCount 文書の分割単位件数
 * @param ingestedAt ingested日時
 * @param chunkIds 文書の分割単位識別子一覧
 */
record StoredVectorDocumentEntry(
    String docId, String source, int chunkCount, String ingestedAt, List<String> chunkIds) {

  /** 利用先へ渡せるよう、項目の形式に変換する。 */
  VectorDocumentEntry toEntry() {
    return new VectorDocumentEntry(docId, source, chunkCount, ingestedAt);
  }
}
