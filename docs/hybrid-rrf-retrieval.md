# 独立候補検索とRRF

文書検索には既に加重dense/lexical検索、隣接チャンク参照、文書単位集約、汎用のHTTP rerankがある。新経路は既存の取り込み・削除・SQLite vec index・rerankを再利用する。

```yaml
rei:
  vector-document:
    retrieval:
      enabled: true
      candidate-limit: 40
      rank-constant: 60
```

既定は`false`で従来の検索・スコアを使う。環境変数は`REI_VECTOR_DOCUMENT_RETRIEVAL_ENABLED`、`REI_VECTOR_DOCUMENT_RETRIEVAL_CANDIDATE_LIMIT`、`REI_VECTOR_DOCUMENT_RETRIEVAL_RANK_CONSTANT`。candidate-limitは1..256、rank-constantは1..1000（未設定の0は40/60に補完）。新経路の文書topKは1..64。

新経路は`Query -> lexical + dense -> chunk IDによるRRF -> 文書集約 -> 既存rerank -> 最終topK`。独立した`RetrievalCandidates` portをSQLiteとlazy wrapperが実装する。lexicalは既存の語句coverageと隣接参照、denseは正のcosine類似度を使い、語句一致でdense候補を除外しない。lexical候補はdense近傍一覧外からも取得する。BM25/FTSや学習済みsparse modelは追加していない。

両経路には同じsource/docIdフィルタとthresholdを渡し、topKの前に適用する。thresholdは各経路の既存スコア（denseのcosine / lexicalの語句coverage・隣接減衰）の条件であり、RRF後スコアの条件ではない。隣接チャンクの参照もdocIdとsourceの双方を一致させる。Doc IDを異なるsourceへ再利用しても、別sourceの語句一致を借りない。この境界修正は従来検索にも適用する。

RRFは各経路で重複IDを一度だけ数え、`sum(1/(rankConstant + rank))`を`(rankConstant+1)/2`で正規化する。二経路とも1位なら1、片経路だけ1位なら0.5。片経路が空でも二経路として正規化する。これは順位統合のスコアで、確率・信頼度・cosineではない。同点はID順。文書の検索スコアは最良チャンクのRRFスコアとし、従来のlexical加点を再適用しない。

各経路の候補数は`max(文書topK*4, candidate-limit)`で上限256、統合後は文書topK*4チャンクまで。チャンクmetadataに`retrievalMode=rrf`、`rrfScore`、実際に取得した`denseRank`/`lexicalRank`を付ける。入力metadataの古い順位は取り除く。source/docId/本文は既存の保存文書から取得する。文書単位の集約・snippet・隣接本文の生成は既存処理を使う。

`CandidateReranker` portには既存`RerankService`が実装として接続する。rerankの有効設定・endpoint・model・credentials・timeout・レスポンス検証・失敗時の候補順維持は従来通り。新しいLLM呼び出しはない。dense query embeddingは既存モデルを一度使う。lexical候補の計算自体にはquery embeddingが不要だが、共有vec indexの初回初期化には既存embedding次元の解決が必要。

空queryは呼び出しなし。zero query embeddingならdenseを空としてlexicalを利用する。未対応filter・backend・DB/embedding例外は黙ってfilterを外さず失敗させる。stage間のinterruptを伝播する。両候補検索は逐次の独立読取で、同時取り込み時の一つのDB snapshotは保証しない。

Skill検索へのembedding/RRF適用、FTS/BM25、候補集合の実データ評価・score calibrationは今後の対象。Document RAGの新規システムは追加していない。

## BM25 follow-up

明示opt-inのSQLite FTS5/BM25を後続で追加した。設定・語句処理・閾値・transaction同期・再起動時再構築の範囲は[document-bm25-retrieval.md](document-bm25-retrieval.md)を参照。上記のFTS/BM25未実装記述は初期実装時点の記録である。学習済みsparse・実データ品質評価は引き続き残る。
