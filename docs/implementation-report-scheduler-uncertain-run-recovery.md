# Scheduler 不確定Run復旧 実装レポート

Shell `/timer reconcile` を追加。観測Run IDと副作用不明acknowledgementを要求し、現在のProjectキューで待機/実行中なら拒否する。dispatch受付と直列化し、SQLiteのProject/ID/Run/状態を比較してFAILED / uncertain_run_reconciledへ移す。元予約を再実行せず、同じSessionの別予約の処理を解除する。

現在のclaimだけが結果を保存する。旧callbackが新Runのbusy状態を解除しないよう、claim identityを比較してslotを解放する。破棄されたキューworkは無効claimを実行しない。

TDD: 未実装APIのcompile failureでRed確認。追加5テストで再読込/非再実行/別予約解除、stale ID/Project/terminal拒否、queued/running拒否、Shell ack/queue除去後の復旧、旧callbackの新claimへの非干渉を確認。全体 -Pfull test は2,861 tests / 548 suites、failure 0 / error 0。git diff --check成功。

副作用成否は不明として保持する。外部プロセス停止と成果物照合は操作者が行う。Native/Web専用復旧画面、自動復旧、cron反復は未対応。
