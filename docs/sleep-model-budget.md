# Sleepの共有モデル予算

```yaml
rei:
  memory:
    sleep:
      max-llm-calls: 10
      max-total-tokens: 30000
```

両方とも既定0で無効。環境変数は`REI_MEMORY_SLEEP_MAX_LLM_CALLS`と`REI_MEMORY_SLEEP_MAX_TOTAL_TOKENS`。呼出回数は0..1000、token上限は0以上を設定できる。片方だけ有効にすることもできる。

1回のSleep処理で、記憶候補の抽出と各候補の意味的な解決が同じ予算を使う。Manual Sleep、preview、Auto Sleepは同じSleepServiceを通る。新しいGoal・Chat Run・予算DBは追加しない。完全一致の重複、低評価候補のIGNORE、比較先なしのNEWなど、既存の決定的な解決は追加のモデル呼出しを消費しない。

モデル実呼出し直前に回数を予約し、進捗・候補ごとの処理で補充しない。Providerのstreamを集約したtotalTokens（入力＋出力）を一度だけ計上する。回数上限ではLLM_CALL_BUDGET_EXCEEDED、token超過ではTOKEN_BUDGET_EXCEEDED、usage欠落・0・負・報告前のProvider障害ではTOKEN_USAGE_UNKNOWNで停止する。token上限だけが無効ならusage欠落を許容する。

ちょうどtoken上限の応答は受け入れられるが、次のモデル呼出しは拒否する。その後の解決が決定的処理だけで済むなら、記憶を保存できる。超過や不明状態はJSON解析・計画の確定より前に止める。length応答でも既知usageは計上する。

Sleepは全候補を計画した後に記憶と処理済み位置を同じトランザクションで保存する。予算停止では部分的な記憶変更やcheckpoint進行を行わず、通常Sleepの失敗監査を保存する。previewは失敗監査も保存しない。既存の入力量・出力量・timeout・取消・活動中断・同一Session実行除外を維持する。

旧custom extractor/resolverは呼出回数上限では各model port呼出しを予約する。token上限を有効にする場合、usageを報告する新しいportへの対応が必要で、未対応なら実行前に拒否する。旧API・lambda・Sleep設定constructorは維持する。

これは**1回のSleep処理の報告後の停止条件**であり、請求金額の厳密な上限ではない。次の明示SleepやAuto Sleepの次回試行では新しい予算になる。実行を跨ぐ永続使用量上限や、Auto Sleep全期間の費用上限ではない。既存のAuto Sleep retry interval・idle条件を変更せず、予算失敗に対する自動再試行を禁止する設定でもない。

Chat／Goalの上限とは独立している。旧`/memory`の独立consolidate/summarize、RunContextなしの要約、CLI、embedding/rerankは今回の範囲外。実Providerのusage品質や記憶の意味品質は未評価。
