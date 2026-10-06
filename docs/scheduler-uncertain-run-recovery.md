# Scheduler 不確定Runの手動復旧

`/timer show <timerId>` と `/timer history <timerId>` で保存状態とRun IDを確認し、実行元と成果物を照合した後に操作する。

```text
/timer reconcile <timerId> --run-id <表示されたrunId> --acknowledge-uncertain-side-effects
```

Project・timer ID・RUNNING状態・Run IDを比較して、FAILED / uncertain_run_reconciledへ移す。成功を推定せず、元予約をSCHEDULEDへ戻さず、自動再実行しない。再起動後も履歴とRun IDを保持する。同じSessionで待機する別予約は通常のScheduler設定・Policy・FIFOに従って処理できるようになる。

現在のProjectキューで受付・待機・実行中の場合は拒否する。dispatch受付とreconcileは同じサービスで直列化し、古いcallbackは現在のclaimが有効な場合だけ保存する。古いcallbackが後続Runのbusy状態を解除しない。

キュー待ちRunを通常の停止操作で除去した後、残った予約を手動で終了させる用途にも使える。元予約を再実行したい場合は副作用を照合して新しい予約を作成し、明示activateする。別プロセスの外部処理はこのキューで観測できないため、停止と照合は操作者が行う。

Shellの人間向け操作で、LLM Toolでは公開しない。Native/Web専用復旧画面、自動副作用照合、cron反復は対象外。
