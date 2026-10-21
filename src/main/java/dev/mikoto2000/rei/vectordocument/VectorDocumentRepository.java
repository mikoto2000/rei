package dev.mikoto2000.rei.vectordocument;

import java.util.List;
import org.springframework.ai.document.Document;

/** ベクトル文書を保存・検索するための窓口。 */
public interface VectorDocumentRepository {

  /** 指定された内容へ切り替えるため、別取得元を置き換える。 */
  VectorDocumentEntry replaceBySource(
      String docId, String source, String ingestedAt, List<Document> documents);

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  List<VectorDocumentEntry> list();

  /** 対象を管理から外すため、別文書の識別子を削除する。 */
  boolean deleteByDocId(String docId);

  /** 対象を管理から外すため、別取得元を削除する。 */
  int deleteBySource(String source);
}
