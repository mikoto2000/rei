# Goal 不確定Runの手動復旧

再起動後に保存状態がRUNNINGのまま残った場合、まず `/goal show <goalId>` と `/goal history <goalId>`、現在の成果物を確認する。

```text
/goal reconcile <goalId> --run-id <表示されたcurrentRunId> --acknowledge-uncertain-side-effects
```

claim後に試行Runを開始していない場合だけ `--run-id none` を使う。指定Run IDが保存状態と一致しない場合、Projectが違う場合、現在のプロセスのProjectキューで受付・待機・実行中の場合は拒否する。復旧とGoalの新規Run受付・継続受付は同じサービス内で直列化する。

操作はPAUSEDへ移し、旧claimを無効化する。未確定の試行はBLOCKED / uncertain_run_reconciledとして履歴に残す。試行回数とLLM消費予算を減らさず、完了証拠を作らず、自動再実行しない。古い完了callbackや予約は無効になる。

副作用の結果は不明のままである。別プロセスの外部処理の終了まではこの操作で確認できないため、実行元停止と既存成果物の照合は操作者が行う。確認後は `/goal verify` で独立検証するか、必要な場合だけ `/goal run` で明示再開する。再開時のChatにも過去Runの副作用が不明であることを伝える。予算が枯渇しているGoalには追加予算を付与しない。

これはShellの人間向け操作で、LLM Toolとしては公開しない。Native/Web専用復旧画面、自動復旧、外部副作用の完全照合は対象外。
