# 文書BM25検索 実装記録

## 変更

監査B11のFTS/BM25不足を、既存文書Hybrid検索の明示opt-inとして実装した。SQLite FTS5の全chunk索引でBM25を計算し、denseと同じRRF・文書集約・既存rerankへ接続する。従来の設定・3引数constructor・独自backendとの互換性を保持し、未対応backendへのBM25要求は明示失敗にする。

source/docIdとcoverage閾値をtopKより先に適用する。queryをliteral語句のORへ変換し、FTS演算子を入力から実行しない。語句／文字／topK／SQL時間を制限し、取消を伝播する。初期利用時に既存文書をtransactionで再構築し、追加・置換・全削除経路を同一transactionで索引と同期する。無効設定で更新した場合も既存索引を維持する。

## 検証

初期RedはBM25 method・constructor未実装のcompile失敗。テストのfilter builder式と必須ingestedAt fixtureも修正した。実SQLiteのBM25順位・頻度と文書長・sourceフィルタ・coverage閾値・再生成／backfill・置換失敗rollback・各削除経路・無効設定からの更新を確認した。

追加設定テストでSpring Binderが値を読み込まないことを検出した（1 error / 0 failure）。canonical constructorへConstructorBindingを明記しGreenにした。literal演算子・語句と部分文字列の区別・blank・未対応filter・文字／topK上限・開始前取消・embedding呼出なし、RRF metadataと文書集約／rerank、設定bindingと遅延初期化を検証した。

最終関連テスト（SqliteVectorStoreTest・HybridRetrievalTest・VectorStoreConfigurationTest・HybridDocumentRetrievalTest・LazySqliteVectorStoreTest・VectorDocumentServiceTest）はPASS。全体回帰は3122 tests / 594 suites、failure/error/skip各0。Javaのみの変更でNative/Reactは再実行していない。

feature aa1f38aaをPushし、main 7aef5f4eへMergeした。Merge後の同じ関連テストもPASS、main Push済み。設定と検索の限界は[document-bm25-retrieval.md](document-bm25-retrieval.md)を参照。学習済みsparse・多言語tokenizer・実データ品質評価等は引き続き残件。
