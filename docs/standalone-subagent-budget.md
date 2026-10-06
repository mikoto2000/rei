# 親RunなしSubAgentの共有モデル予算

`rei.subagents.standalone-max-llm-calls`（`REI_SUBAGENTS_STANDALONE_MAX_LLM_CALLS`）と `standalone-max-total-tokens`（`REI_SUBAGENTS_STANDALONE_MAX_TOTAL_TOKENS`）で、親 AgentRunScope／明示予約のない直接SubAgent呼出・並列バッチに上限を設定する。既定は両方0で従来経路を維持する。呼出上限は0..1000、tokenは非負。0の項目は無効。

直接実行は1呼出内、並列実行は受付済みバッチ全体の同一予約を使う。子cycle・出力修復・独立意味検証も同じ回数／報告tokenを消費し、子や修復ごとに補充しない。明示的な親Run/Goal予約が渡される場合は既存予約を優先する。次の独立バッチは新しい予算になる。Project跨ぎ永続予算ではない。

実モデル送信前にatomic予約し、応答の報告usageを構造化結果・Tool・意味検証より先に計上する。token未知・非正値・provider失敗・timeout時の未報告は共有unknown状態で後続を止める。超過は報告費用を保持して停止。正確な上限の結果は利用できるが次の呼出を禁止する。既存入力事前検査・worker2・受付1・部分結果・順序・取消・timeout・診断履歴を維持する。

並列で既に予約・送信済みの最大2呼出は、最初のusage判明前に進行し得る。両方の費用を計上し、後続を止める報告token予算であり、provider側hard spend limitではない。追加embedding/rerank等や金額換算、未所有呼出全体の永続費用、DAG／合意形成／永続復旧は別の候補。live providerを使用せずローカルstream fixtureで検証する。
