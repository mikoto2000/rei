# rerank の親 Run/Goal モデル予算

`rei.rerank.inherit-run-model-budget=true`（環境変数 `REI_RERANK_INHERIT_RUN_MODEL_BUDGET`）で、既存 rerank HTTP 呼出を ModelCallBudgetScope の親 Run/Goal 予算へ接続する。既定 false。scope がない呼出、無効 endpoint、空候補は既存経路を維持する。

HTTP 送信前に1回予約し、応答の `usage.total_tokens` を並べ替え結果の利用より先に計上する。正整数の報告 token を要求し、未知・非正値・型不正・overflow・provider 障害は token 上限有効時に停止する。超過は実費を計上してから停止し、ちょうど上限の結果は利用できるが次の HTTP 呼出を禁止する。取消・回数枯渇も送信前に停止する。

予算付き応答は最大64 KiB・深さ32の strict JSON とし、重複キーと末尾の別 JSON を拒否する。既知 usage の結果不正では費用を保持して従来の順序へ fallback する。Semantic Skill の fallback は typed 予算停止を吸収しない。Goal の永続累積費用は再起動後も保持する。

対象は親 scope を持つ Run/Goal の呼出回数と provider 報告 token。usage を返さない provider の token 予算は fail closed となる。検索単位・金額への換算、未所有の直接呼出、provider 内部 cycle、送信後の provider 側 hard spend limit は制御しない。既存 endpoint／認証／timeout とランキング検証を維持し、既定では新しい有料呼出を追加しない。
