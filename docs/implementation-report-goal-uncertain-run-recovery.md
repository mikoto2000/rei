# Goal 不確定Run復旧 実装レポート

Shell `/goal reconcile` を追加。観測したRun IDと副作用不明の明示acknowledgementが必要。現在のProjectキューの受付/待機/実行中Runを除外し、サービスのRun開始・継続受付とreconcileを直列化する。SQLiteではProject/Goal/状態/Run IDを比較して更新する。

復旧はPAUSEDへの遷移のみで、自動再実行/成功証拠/予算返却を行わない。古いclaimを無効化し、未確定試行をBLOCKEDとして保存する。明示再開時のChatに副作用不明を伝える。Native/Web専用復旧画面は未対応。

TDD: 未実装APIのcompile failureでRed確認。追加6テストで再読込・予算保持・旧claim無効化・stale ID/Project拒否・試行前claim・queued/running拒否・Shell acknowledgementと明示再開・Projectキューpresenceを確認。全体 -Pfull test は2,856 tests / 547 suites、failure 0 / error 0。git diff --check成功。

現在のプロセス外の処理はこのキュー照合で観測できない。操作者が停止と成果物を確認してから明示復旧する。LLM Toolでは公開しない。
