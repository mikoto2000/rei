# 複数SubAgentへの並列委譲

CHATの `delegateTasks` は、独立した1〜8件の依頼を最大2workerで実行し、入力順の個別結果を返す。単独の `delegateTask` は維持する。親は明示された委譲意図を尊重し、依存関係がある作業を並列化しない。

```json
{
  "requests": [
    {"id":"review-a","agent":"reviewer","task":"Aを調査","context":null},
    {"id":"review-b","agent":"reviewer","task":"Bを調査","context":null}
  ]
}
```

idは1〜64文字の英数字・ハイフン・underscore、バッチ内で一意。agentは登録済みの定義ID。taskは空白のみを許さず最大16,384文字、contextは任意で最大32,768文字。全件を実行前に検査し、不正な依頼や不明agentを含むバッチでは子を開始しない。定義は各子の開始時にRunnerが取得するため、実行中のregistry reloadは開始前の子に影響し得る。

並列バッチはアプリ内で1件のみ受け付ける。別バッチが動作中ならREJECTEDを返して子を開始しない。共有executorは2worker・queue上限8であり、呼び出しごとにworkerを増やさない。単独delegateTaskの同時実行はこのバッチ枠に含めない。

バッチ全体に120秒の期限を適用し、待機時間も含める。期限後に待機依頼を開始せず、期限切れ・親キャンセル・呼び出しthreadの割り込みでは全futureをcancelし、queueを除去する。子Runnerの割り込み処理が既存のstream/Toolキャンセルを行う。割り込みを無視するproviderは即時停止を保証できないが、executorのworker上限は維持する。アプリ終了時もexecutorを停止する。

各子は親のRun ID・Project・Session・SHELL/WEB sourceを捕捉し、独立した子Run ID・履歴・Tool contextで動く。既存のread-only Tool Policy、Permission、maxSteps、timeout、検証、任意repairをそのまま使う。子の並列・単独再帰委譲は禁止する。親の会話履歴とWorking Setを共有しない。依頼順に並べるために子を順次実行することはない。

返却値は `status` と `items`。各itemはid、agent、status、任意のSubAgentResultを含む。全子の実行がCOMPLETEDならバッチCOMPLETED、一部だけならPARTIAL、完了なしならFAILED。バッチ自体の期限切れはTIMEOUT、停止はCANCELLED、受付拒否はREJECTED。子の実行状態と内側のSUCCESS/FAILURE/PARTIALは別であり、バッチCOMPLETEDはタスク全体の成功を保証しない。親は各structuredOutputを確認する。

停止前に完了した結果は保持する。futureがcancelされた依頼や予期しない例外ではresultはnullで、Run IDや診断を捏造しない。例外本文を返さず、他の子の結果を失わない。独立LLMでの要約・多数決・合意形成、DAG、永続バッチ復旧、全子共通のLLM/token予算は未実装。
