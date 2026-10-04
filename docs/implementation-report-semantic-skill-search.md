# Semantic Skill Search 実装報告

ブランチ: `codex/semantic-skill-search`

既存keyword候補の前段を拡張し、任意のmetadata embedding、共有RRF、既存rerankerを統合した。明示指定優先と既存の最終LLM selectorを維持する。既定無効で、providerの解決とネットワーク呼び出しは有効時のみ行う。

TDDのRedは `target/skill-semantic-red.log` に記録。言い換えによる検索、無効Skill/本文の除外、metadata cacheの更新、現在のSkillオブジェクトの返却、障害backoff、上限超過fallback、キャンセル、不正vector、provider timeoutと遅延解決を検証した。新規8テストと既存関連テストが通過。全体回帰は2,774テスト / 535スイート、失敗・エラー・skipは0。初回回帰で設定YAMLの重複キーを検出し、除去後に全体を再実行してPASS。

利用方法と境界は [semantic-skill-search.md](semantic-skill-search.md) を参照。外部モデルを用いた検索品質評価と永続indexは未実装。
