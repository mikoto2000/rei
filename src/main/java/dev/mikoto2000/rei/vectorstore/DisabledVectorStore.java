package dev.mikoto2000.rei.vectorstore;

import dev.mikoto2000.rei.vectordocument.VectorDocumentEntry;
import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * 無効状態ベクトルの記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Provides empty retrieval results without a database or embedding model.
 */
public class DisabledVectorStore implements VectorStore, VectorDocumentRepository {
  /** 関連文書の検索で用いる類似度検索を返す。 */
  @Override
  public List<Document> similaritySearch(SearchRequest request) {
    return List.of();
  }

  /** 関連文書の検索で参照するOSの機能クライアントを返す。 */
  @Override
  public <T> Optional<T> getNativeClient() {
    return Optional.empty();
  }

  /** 新たな対象を登録し、後から利用できるようにする。 */
  @Override
  public void add(List<Document> documents) {
    throw disabled();
  }

  /** 指定された対象を削除する。 */
  @Override
  public void delete(List<String> ids) {
    throw disabled();
  }

  /** 指定された対象を削除する。 */
  @Override
  public void delete(Filter.Expression filterExpression) {
    throw disabled();
  }

  /** 指定された内容へ切り替えるため、別取得元を置き換える。 */
  @Override
  public VectorDocumentEntry replaceBySource(
      String docId, String source, String ingestedAt, List<Document> documents) {
    throw disabled();
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @Override
  public List<VectorDocumentEntry> list() {
    throw disabled();
  }

  /** 対象を管理から外すため、別文書の識別子を削除する。 */
  @Override
  public boolean deleteByDocId(String docId) {
    throw disabled();
  }

  /** 対象を管理から外すため、別取得元を削除する。 */
  @Override
  public int deleteBySource(String source) {
    throw disabled();
  }

  /** 関連文書の検索で用いる無効状態を返す。 */
  private IllegalStateException disabled() {
    return new IllegalStateException("embedding は無効です (rei.embedding.enabled=false)");
  }
}
