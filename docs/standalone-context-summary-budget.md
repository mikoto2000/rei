# RunContextなし会話要約の費用予算

`rei.context-compression.standalone-summary-max-llm-calls`（0〜1000）と `standalone-summary-max-total-tokens`（0以上）は、RunExecutionContext がない context 組立ての要約に適用する。各0は無効。環境変数は `REI_CONTEXT_STANDALONE_SUMMARY_MAX_LLM_CALLS` / `REI_CONTEXT_STANDALONE_SUMMARY_MAX_TOTAL_TOKENS`。

ContextAssembler の1回の組立てでは、会話履歴と実行ログの要約が同じ呼出回数／報告total tokens予算を共有する。LlmConversationCompressor を直接呼ぶ場合は1回ごとに独立する。モデル呼出直前に予約し、応答usageを summary品質判定・保存・縮退処理より先に計上する。ちょうどtoken上限の応答は使えるが、次呼出は止める。未知usage・超過・provider失敗・timeoutは固定制御signalで停止し、ContextAssemblerのdegraded処理で吸収しない。

1つ目の summary が保存済みで2つ目が停止した場合、1つ目を消さない。停止した summary の処理済みsequenceを進めない。timeout は既存の stream取消を使い、既存 Run/Goal がある経路では親の共有予算を引き続き使う。独自 ConversationCompressor は予算付き overload で実呼出とusageを計上できなければ停止する。既定無効時は従来の functional interface 経路を維持する。

この上限は同一組立て内の報告済みtokenであり、永続費用・金額・その後の親Chat応答を累積計上するものではない。複数組立て／再起動を跨ぐ永続予算は Run/Goal の予算経路を使用する。出力token・入力context・summary timeout の既存上限と併用する。
