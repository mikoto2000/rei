# 文書Hybrid検索のBM25

```yaml
rei:
  vector-document:
    retrieval:
      enabled: true
      bm25-enabled: true
      candidate-limit: 40
      rank-constant: 60
```

両方のenabled設定がtrueのとき、SQLite FTS5のBM25をlexical候補に使用する。既定はfalseで、従来のcoverage検索を維持する。dense候補とchunk IDでRRFを行い、既存の文書集約・rerank・最終topKへ接続する。独自backendがBM25未対応の場合は明示エラーにし、別方式へ黙って切り替えない。

FTS5の[公式仕様](https://www.sqlite.org/fts5.html#the_bm25_function)に従い、SQLiteの負のBM25値を反転して大きい順に並べる。metadataにはlexicalMode、bm25Score、lexicalCoverageを保持する。BM25は確率ではないため、similarityThresholdは従来の語句coverageによる候補の足切りに使用する。source/docIdフィルタと閾値を適用してからtopKを選ぶ。BM25統計は索引全体のchunkを対象とし、フィルタ毎に再学習しない。

unicode61 tokenizerを使用する。queryはUnicode文字・数字の語句へ分割し、2文字以上の重複しない語句を引用したOR条件に変換する。入力のOR/NOT/引用符/アスタリスクをFTS式として実行しない。4096文字・64語句・topK最大256、SQL timeout 5秒、開始前と結果取得中の取消確認を持つ。BM25経路はembeddingを呼ばない。日本語の形態素分割・部分文字列・隣接chunkの擬似一致は追加しておらず、従来lexicalと結果が異なる。

初回のstore利用時に、BM25が有効なら既存vec0文書から索引をtransaction内で再構築する。以降の追加・source置換・各削除はvec0とFTSを同じtransactionで更新し、失敗時は両方をrollbackする。設定を無効化した場合も、本バージョンは既存FTS索引を維持する。旧バージョンで更新した後は再有効化時の再構築で修復する。起動時の再構築には索引全体の処理時間とDB内の追加容量が必要となる。外部からDBを直接更新する運用や、異なるバージョンを同時起動する運用は保証しない。

学習済みsparse、実データの検索品質評価、score calibration、多言語tokenizerは引き続き別対応。追加LLM・外部検索サービスは使用しない。
