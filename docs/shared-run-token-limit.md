# Runの共有・報告済みtoken上限

`rei.llm.output-limit.max-total-tokens-per-run`で、モデルが応答後に報告した合計token数に基づくRun停止上限を設定できる。既定0は無効。正の値のみ有効で、負の値は設定エラー。

```yaml
rei:
  llm:
    output-limit:
      max-total-tokens-per-run: 50000
```

入力と出力を含むProviderのtotalTokensを計上する。親Chatの各Tool cycle、出力上限planner、暗黙Skill選択、ToolContextでRun予算を継承したSubAgentの初回・cycle・修復・意味検証・再検証は同じRunへ計上する。並列子も親の同期したカウンタを共有する。モデル応答のusage集約後に1回だけ計上し、streamの各chunkを重複して足さない。

報告総量が上限を超えたらTOKEN_BUDGET_EXCEEDEDで停止し、その応答が要求したToolを実行しない。ちょうど上限までの応答は受け入れるが、次のモデル呼出しは開始しない。親の事前予約後にSkill選択等が上限へ達した場合も、親のモデル呼出し直前に再確認する。

使用量が欠落・0・負の場合、または子のモデル呼出しがusage報告前に失敗／取り消された場合は、不明状態として後続を止める。TOKEN_USAGE_UNKNOWNを通知し、不明使用量を0として進めない。Skill／plannerの計上失敗も通常fallbackに隠さない。設定を無効にした場合は従来の使用量欠落を許容する。

これは**報告後の停止条件**であり、APIの請求金額や送信済み呼出しの厳密な上限ではない。最初の応答や、使用量判明前に開始済みの並列呼出しで超過し得る。既存の1応答max-output-tokens、呼出回数・step・timeout・Policyも併用する。途中のstream表示を取り消せるとはしない。

範囲は上記の明示Run経路。Goalが複数Runを跨ぐ総token量の永続上限、独立Sleep／記憶整理、context圧縮用の独立要約LLM、External Agent CLIの使用量、embedding／rerank、RunContextなしの直接手動委譲は対象外。これらを0tokenと証明したり、全機能の費用上限を保証したりする設定ではない。
