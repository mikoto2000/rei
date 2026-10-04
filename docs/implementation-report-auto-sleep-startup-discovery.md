# A1 Auto Sleep 起動時の保存Session発見

保存済みSessionを、新しい会話の終了を待たずにAuto Sleep候補へ取り込む。SessionRepositoryの起動時読込済みmetadata snapshotを初回idle時に一度取得し、最大256件ずつ走査する。候補も最大256件で、件数閾値未満の候補を除き空きを作る。登録Project境界とSession IDに含まれるProjectの整合を確認する。

既定無効を維持し、memory.enabled、idle/busy、未処理件数、試行間隔、単一worker、活動時cancel、既存Sleep checkpointを再利用する。追加のディスクmetadata走査や直接のLLM呼び出しは行わない。

検証: 未実装setter参照のcompile failureでRedを確認。AutoSleepServiceTestは13件成功。追加5件は保存Sessionの発見、disabled/busy/recent activity gate、300件の分割走査、登録外/所有不一致除外、JSONファイルの再読込を確認。全体 -Pfull test は2,845 tests / 545 suites、failure 0 / error 0。git diff --check成功。

候補が256件の処理待ち・再試行待ちで埋まっている間、次のmetadataの取り込みは待機する。起動後の外部metadata継続監視、cron/日次/Session終了triggerはDeferred。
