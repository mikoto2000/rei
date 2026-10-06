# Sleep のプロジェクト累積モデル予算

`rei.memory.sleep.max-llm-calls-per-project` と `max-total-tokens-per-project` は、それぞれプロジェクトごとの累積呼出回数・provider が報告した total tokens の上限。既定 `0` は無効。環境変数は `REI_MEMORY_SLEEP_MAX_LLM_CALLS_PER_PROJECT` / `REI_MEMORY_SLEEP_MAX_TOTAL_TOKENS_PER_PROJECT`。従来の `max-llm-calls` / `max-total-tokens` は1回の Sleep の上限として併用できる。

SQLite の `sleep_model_usage` にモデル呼出直前の予約と使用量を保存する。Manual/Auto Sleep、候補抽出・意味解決、プレビュー、失敗後の再実行、アプリ再起動で共有する。記憶・処理済みカーソルの transaction が rollback されても費用を戻さない。入力検査・既存同一記憶の処理など、モデルを呼ばない処理には課金予約を行わない。

報告値が上限を超えた応答は計上後、解析・記憶保存前に停止する。ちょうど上限ならその応答は利用可能だが次の呼出を禁止する。provider 障害・使用量不明・取消で未報告の予約が残った場合、token 制限有効時は `TOKEN_USAGE_UNKNOWN` で停止する。同一プロジェクトに未報告の呼出がある間も、新しい呼出を止める。別プロジェクトの予算は独立する。

これは金額・モデル別単価・embedding/rerank/CLI の費用ではない。制限を有効にした呼出からのみ記録し、過去の未計上 usage を推測しない。上限変更は既存カウンタを保持し、超過後に上限を引き上げても計上済み費用を消さない。未知使用量を自動でゼロ扱いする復旧・予算リセット操作は設けない。既定無効時はモデル呼出の永続予約を行わず、従来の動作を維持する。
