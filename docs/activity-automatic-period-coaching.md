# 週次・月次Coachingの自動通知

保存済みActivityの完了済み暦週/月を使い、既存PeriodCoachingの助言をアプリ内へ通知する。分析と文面は既存の決定的処理で、追加LLM・撮影・Tool実行・Task作成は行わない。Activity journal全体のユーザー基準であり、現在選択Projectの成果を推測する機能ではない。

設定例:

```yaml
rei:
  activity:
    enabled: true
    coaching:
      automatic-enabled: true
      weekly-enabled: true
      monthly-enabled: true
      check-interval-seconds: 3600
```

自動通知の既定は無効。設定で有効にするだけでは通知しない。既存 `/activity coaching configure ...` で基準を保存し、`/activity coaching on` で保存設定も明示的に有効にする。`configure` は自動/手動の助言を一旦無効にする。`off` は両方を抑制する。`status` と `on` の表示に自動通知設定を含める。Activity無効・pause中・Chat実行中・配信基盤なしの場合も抑制する。

チェック間隔は60〜86400秒。専用workerは1つ・待ちqueueなしで、処理中に重ねて実行しない。各回は直近の完了済み月/週だけを最大2件評価し、通知は最大1件。月次を先に検査する。月次と週次、手動表示は同じ期間キー・設定revision・SQLite原子的予約・共通cooldownを使う。開始直後の最初のチェックも設定間隔後で、再起動後に過去期間をまとめてbackfillしない。

最小観測量/率・判定不能率・両期間完了・ユーザーの分類比率という既存品質gateを維持する。条件内なら助言なし。成果や生産性の低下を断定せず、観測推定と未観測の限界を明示する。評価中のユーザー操作・Chat開始・pauseや、予約時/配信直前の設定変更を検査し、古い文脈では配信しない。

通知は既存Activity BehaviorのAgentMessage経路を再利用し、`triggerType=PERIOD_COACHING_WEEK/MONTH` で区別する。既存のProject/Session選択・Agent Event・会話履歴・Native表示に従う。通知先はその時点の選択先であり、画面から推測したProjectへ割り当てない。音声は既存通知音声設定に従う。短時間娯楽判定のBehavior基準/cooldownとは別だが、Coaching内の週/月/手動cooldownは共通である。

予約は配信前に消費する。配信失敗・予約後の文脈変更・クラッシュでも解放/自動再送せず、再起動後も同じ期間を重複通知しない。`ALREADY_SHOWN` は既存の予約があるという意味で、配信成功の保証ではない。DBと外部表示の分散transactionは保証しない。保存するCoaching予約は期間キー・理由・時刻・設定revisionだけ。配信本文の会話履歴保存は既存Activity通知と同じ境界を使う。
