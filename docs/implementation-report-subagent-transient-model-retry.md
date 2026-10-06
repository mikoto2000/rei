# SubAgent transientモデルretry 実装記録

C15のmodel障害retryへ、既定0・最大3・invocation共用の限定再試行を追加した。Spring AI transient型かつ応答受信前だけ、100ms backoff後に同じPromptを再呼出する。モデル応答の取得部分に限定し、既実行Toolを再生しない。maxSteps・共有呼出予算・報告token未知停止・全体timeout・cancelを継承し、通常cycle／repair／独立judgeで上限をresetしない。

未実装property／結果APIのcompile Red→4テストGreen→9新規テスト・関連PASS。transient成功／既定無効／部分応答・非transient拒否、step／呼出cap／未知usage停止、上限・timeout、Tool receipt非再生・Tool障害非retry、repair／judge共有、cancel中のbackoff、binding・旧constructorを検証した。実stream開始後だけattemptsを加算し、失敗分類を固定コードとして保持する。timeout fixtureは、schedulerが呼出前に時間切れとなる正当な0 callも許容する。

全体回帰は3267 tests / 608 suites、failure/error/skip各0。feature a8f1338aをCommit/Pushしmain efc96ef3へMerge済み。Merge後関連テストPASS、main Push済み。Java/configのみでNative/React変更なし。

[再試行対象・予算・SDK境界・固定履歴](subagent-transient-model-retry.md)。Tool障害retry・永続resumeなどは監査表で引き続き管理する。
