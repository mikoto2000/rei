package dev.mikoto2000.rei.vectorstore;

import dev.mikoto2000.rei.vectordocument.VectorDocumentEntry;
import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import tools.jackson.databind.json.JsonMapper;

/** 必要時に取得するSQLiteベクトルの記録を保持し、後続の処理で参照できるようにする。 */
public class LazySqliteVectorStore
    implements VectorStore, VectorDocumentRepository, RetrievalCandidates {

  /** データ取得元。 */
  private final DataSource dataSource;

  /** 埋め込み表現モデル。 */
  private final EmbeddingModel embeddingModel;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final JsonMapper objectMapper;

  /** bm25有効状態。 */
  private final boolean bm25Enabled;

  /** 委譲先。 */
  private volatile SqliteVectorStore delegate;

  /** 関連文書の検索を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public LazySqliteVectorStore(
      DataSource dataSource, EmbeddingModel embeddingModel, JsonMapper objectMapper) {
    this(dataSource, embeddingModel, objectMapper, false);
  }

  /** 指定された値や利用先を使って、LazySqliteVectorStoreを初期化する。 */
  public LazySqliteVectorStore(
      DataSource dataSource,
      EmbeddingModel embeddingModel,
      JsonMapper objectMapper,
      boolean bm25Enabled) {
    this.dataSource = dataSource;
    this.embeddingModel = embeddingModel;
    this.objectMapper = objectMapper;
    this.bm25Enabled = bm25Enabled;
  }

  /** 新たな対象を登録し、後から利用できるようにする。 */
  @Override
  public void add(List<Document> documents) {
    delegate().add(documents);
  }

  /** 指定された対象を削除する。 */
  @Override
  public void delete(List<String> ids) {
    delegate().delete(ids);
  }

  /** 指定された対象を削除する。 */
  @Override
  public void delete(Filter.Expression filterExpression) {
    delegate().delete(filterExpression);
  }

  /** 関連文書の検索で用いる類似度検索を返す。 */
  @Override
  public List<Document> similaritySearch(SearchRequest request) {
    return delegate().similaritySearch(request);
  }

  /** 関連文書の検索で用いるベクトルによる検索を返す。 */
  @Override
  public List<Document> denseSearch(SearchRequest request) {
    return delegate().denseSearch(request);
  }

  /** 関連文書の検索で用いる語句による検索を返す。 */
  @Override
  public List<Document> lexicalSearch(SearchRequest request) {
    return delegate().lexicalSearch(request);
  }

  /** 関連文書の検索で用いるbm25検索を返す。 */
  @Override
  public List<Document> bm25Search(SearchRequest request) {
    return delegate().bm25Search(request);
  }

  /** 関連文書の検索で参照するOSの機能クライアントを返す。 */
  @Override
  public <T> Optional<T> getNativeClient() {
    return delegate().getNativeClient();
  }

  /** 指定された内容へ切り替えるため、別取得元を置き換える。 */
  @Override
  public VectorDocumentEntry replaceBySource(
      String docId, String source, String ingestedAt, List<Document> documents) {
    return delegate().replaceBySource(docId, source, ingestedAt, documents);
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @Override
  public List<VectorDocumentEntry> list() {
    return delegate().list();
  }

  /** 対象を管理から外すため、別文書の識別子を削除する。 */
  @Override
  public boolean deleteByDocId(String docId) {
    return delegate().deleteByDocId(docId);
  }

  /** 対象を管理から外すため、別取得元を削除する。 */
  @Override
  public int deleteBySource(String source) {
    return delegate().deleteBySource(source);
  }

  /** 関連文書の検索で用いる委譲先を返す。 */
  private SqliteVectorStore delegate() {
    SqliteVectorStore current = delegate;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (delegate == null) {
        delegate = new SqliteVectorStore(dataSource, embeddingModel, objectMapper, bm25Enabled);
      }
      return delegate;
    }
  }
}
