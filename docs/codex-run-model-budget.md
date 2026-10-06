# 外部 Codex CLI の親 Run/Goal モデル予算

`rei.external-agents.codex.inherit-run-model-budget=true`（環境変数 `REI_CODEX_INHERIT_RUN_MODEL_BUDGET`）で、外部review・resume・修正案生成を親 Run/Goal の既存モデル予算へ接続する。既定false。明示ユーザー要求・read-only CLI隔離・1 Run 1委譲・承認・履歴の従来条件を維持する。

CLI能力確認のhelpは計上せず、実委譲プロセスの呼出直前に1回分を予約する。使用量は完全な成功JSONLの1つのturn.startedから末尾のturn.completed.usageを読み、非負の整数input_tokens＋output_tokensを1回だけ計上する。cached inputやreasoning内訳を別加算しない。token値はprovider報告を使い、正値total・int範囲・duplicate key禁止・深さ32・最大4Mi文字などを確認する。構造化reviewや修正案を解析・保存するより前に計上する。

[公式イベント型](https://github.com/openai/codex/blob/main/sdk/typescript/src/events.ts)では1 turn が1 promptの処理全体に対応し、turn.completedにusageを持つ。この予算の呼出回数1はCLI委譲1回であり、CLI内部の各モデル／Tool cycleの回数を測定・制限するものではない。CLI完了時の報告token合計を親予算に累積し、その後のモデル呼出を制限する。金額やCLI実行中のhard spend limitではない。既存total/inactivity timeout・出力byte上限と併用する。

usage欠落・非正total・非整数・重複／複数turn・不完全／切詰め・失敗／timeoutはtoken有効時にTOKEN_USAGE_UNKNOWNで停止する。既知超過は計上後TOKEN_BUDGET_EXCEEDED、呼出枯渇は有料プロセスの起動前に停止する。予算停止も外部review履歴とterminal eventをFAILEDとして記録してからtyped制御signalを伝播する。超過した修正案はChange Setとして保存しない。取消はCANCELLEDを維持し、未報告Goal予約を自動返金しない。

標準Codex executor以外は予算付きexecuteを実装して実呼出・usageを計上する必要がある。token有効時に非対応executorを呼ばない。token無効時は呼出回数予約のみ対応可能。既定false時は従来のexecutor呼出経路を使う。Goalの作成時に保存した累積token上限を含め、親Run/Goalの設定・復旧・未知usage停止条件を再利用する。
