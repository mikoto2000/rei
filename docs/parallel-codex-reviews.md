# 並列 Codex レビュー

`rei.external-agents.codex.parallel-review-enabled=true`（`REI_CODEX_PARALLEL_REVIEW_ENABLED`）で、実際のユーザーが明示的に依頼した並列レビューを `requestParallelCodexReviews` から実行する。既定false。通常のレビュー依頼・引用された parallel・否定・単一レビュー用slash commandではbatchを許可しない。

入力は1〜4件の独立した `{requestId,task,target,context}`。一意のID（英数/underscore/hyphen、64文字以内）、focus 4000文字、context 6000文字、target 1024文字まで。全対象を現在の登録Project/root内の既存pathとして事前検査する。root/agent/native sessionをTool引数で変更しない。固定2 worker、queue2、受付batch1。BUSYは他Runの委譲権を消費せず、queueを増やさない。

1 batch全体で親Runの外部委譲権を一回消費する。通常review・re-review・resume・fix proposalと相互排他。各workerは同じ所有者のAgentRunScope、read-only REVIEW、既存CLI隔離、保存履歴・終端eventを再利用する。結果を入力ID/入力順で返し、provider失敗はPARTIAL、全成功はCOMPLETED。raw outputや修正案を返さず、結果ごとに独立して評価する。自動Apply・fix・native resume・合意形成・再送は行わない。

各有料CLI開始直前に親Run/Goalの呼出回数を予約する。batchでは単一レビューの `inherit-run-model-budget=false` にかかわらず共有予算を使う。[既存CLI usage検査](codex-run-model-budget.md)による報告tokenを各委譲について計上する。別workerの予算停止も100ms以内の待機区間で検知して他workerを取消す。既に開始した並列呼出の費用を回収する仕組みやCLI内部cycleのhard spend limitではない。

batch deadlineは `parallel-review-timeout`（`REI_CODEX_PARALLEL_REVIEW_TIMEOUT`）で100ms〜120秒、既定120秒。期限時には開始済み保存reviewIdを返し、未確定のprovider結果を成功として扱わない。未開始itemには保存IDがない。取消supplierとworker interruptでこのbatchの管理プロセスのみを止め、STARTED/保存終端を確認してから後続を判断する。token予算有効時に未報告の有料呼出が残ればTOKEN_USAGE_UNKNOWNを親へ伝播し、親モデル処理を継続させない。回数/費用を自動返金しない。

親Run取消は制御signalとして伝播し、queue中の新しいprovider呼出を開始しない。アプリ終了では受付を閉じ、活動batchを取消し、workerを最大2秒待つ。batch deadline自体はprovider/SQLiteの終了待ちを成功保証せず、結果不明は自動再送しない。

複数vendor adapter、DAGや評価合意、durable batch recovery、CLI直接書込は追加候補。既存Codex executorの同時read-only委譲に限定する。テストはローカルSQLite・mock process runnerのみで、実CLI/モデル課金を発生させない。
