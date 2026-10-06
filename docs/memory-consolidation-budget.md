# 旧Memory統合・要約のモデル予算

```yaml
rei:
  memory:
    consolidation:
      max-llm-calls: 2
      max-total-tokens: 12000
```

環境変数は`REI_MEMORY_CONSOLIDATION_MAX_LLM_CALLS`と`REI_MEMORY_CONSOLIDATION_MAX_TOTAL_TOKENS`。既定は両方0で従来の無制限動作を維持する。呼出回数は0〜1000、報告token上限は0以上。Sleepの設定・Run/Goalの予算とは独立した、旧Memory command/serviceの1回の操作予算である。

`/memory summarize`は既存履歴の候補抽出と要約の2つのモデル呼出を、同じ絶対予算で実施する。`/memory consolidate`は抽出の1呼出に適用する。直接のextractCandidates/summarizeは呼び出すたびに新しい操作予算、summarizeCandidatesは抽出と要約を共有する。候補がない場合や空の要約入力では不要なモデル呼出をしない。

呼出直前に回数を予約し、応答usageのtotal tokensを取得してから、JSON候補解析・既存のfallback・要約文字数切詰めへ進む。token上限を有効にした場合はChatResponseを使い、usage欠落／0以下や呼出開始後のprovider障害をTOKEN_USAGE_UNKNOWNとして停止する。通常の予算超過はTOKEN_BUDGET_EXCEEDED、次の呼出を予約できなければLLM_CALL_BUDGET_EXCEEDED。未知／超過後に候補fallbackや要約保存へ進まない。

token上限ちょうどの応答は受け入れ、次の呼出は停止する。抽出4＋要約4、上限8なら両方を完了できる。抽出だけで上限4に達した場合、2つ目の要約呼出は実行しない。合計上限7なら2つ目の応答が返った時点で停止し、summaryを返さない。報告token上限であり、最終呼出の超過は発生し得る。金額・provider retry回数・将来のtoken消費を事前保証するものではない。

`--approve`は従来の保存承認であり、予算超過を許可しない。両phaseの結果が揃う前に保存せず、停止時は固定reasonを`[stopped]`として表示する。既存Runnable commandの出力規約を維持する。取消とwrapped interruptionは制御信号として伝播し、成功や候補fallbackへ変換しない。

token制限を無効にした場合は従来のcontent経路とfallbackを維持する。通常のMEMORY ChatClientは既存のToolなし構成を使う。custom ChatClient/advisorの内部再呼出はこのouter call回数予算に自動統合しない。

追加LLM・新DB・自動trigger・retry・永続予算は追加しない。旧APIの履歴抽出・候補形式・要約文字数・明示保存承認の意味も維持する。Sleep跨ぎ永続予算、CLI／embedding／rerankなどの費用予算、旧履歴のscope改善は引き続き別対応。
