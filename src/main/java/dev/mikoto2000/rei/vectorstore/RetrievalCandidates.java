package dev.mikoto2000.rei.vectorstore;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/**
 * 関連情報の取得で利用できる入力候補を提供する。
 *
 * <p>Independently ranked candidates. Each implementation must apply filters before topK.
 */
public interface RetrievalCandidates {
  /** 関連文書の検索で用いるbm25検索を返す。 */
  default List<Document> bm25Search(SearchRequest request) {
    throw new UnsupportedOperationException("BM25 retrieval is not supported by this backend");
  }

  /** 関連文書の検索で用いるベクトルによる検索を返す。 */
  List<Document> denseSearch(SearchRequest request);

  /** 関連文書の検索で用いる語句による検索を返す。 */
  List<Document> lexicalSearch(SearchRequest request);
}
