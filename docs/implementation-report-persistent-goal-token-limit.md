# Goal永続token上限 実装レポート

## 実装

Run報告token上限の既存portをGoalへ接続し、複数Run・明示再開・再起動を跨ぐ上限と使用量をSQLiteで保持する。設定は既定0で無効、新規Goal作成時に値を保存する。旧Goalは既定無効のまま列追加で移行し、既存constructorも維持する。

Goalのモデル予約時に未報告数を増やし、Providerの集約usageを報告した時に減らす。総量の更新はSQLで原子的に行い、上限超過・使用量不明を既存停止理由へ伝える。停止・取消・不確定Run照合で未報告予約が残る場合は不明状態を永続保持する。claim・次のattempt・追加モデル呼出しは、枯渇／不明なGoalを再実行しない。通常Runのみの設定・既定無効・旧APIは互換。

## 検証

設定setter・Repository constructor・使用量port・永続フィールドの未実装によるRedを確認した。Greenでは、複数Runの累積と再開、再起動時の設定保持、未知usage、未報告Runのreconcile、並列SQLite報告、未予約再報告の拒否、旧DBの移行と旧Goal既定無効、取消と負の設定を確認した。実ChatのGoal単独上限では、超過応答からToolを実行せず、GoalをBLOCKEDにし、再実行前に停止することを検証した。

Goal repository/recovery/loop/gateway/HTTP、実Chat、Run上限、Skill、SubAgent共有予算、planner、設定テンプレートの関連テストはPASS。最終全体回帰はoffline Maven・JDK25・full profileで3072 tests / 588 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactのテストは再実行していない。

最初の全体回帰通過後、予約済みだがusage未報告のまま成功結果を返す経路を追加検証した。次のattemptへ進んで所有権例外になるRedを確認し、未報告予約が残るGoalを次のRun開始前にBLOCKED/token_usage_unknownへ止めるよう修正した。事前予約後・モデル送信前に停止した可能性も、使用量0として扱わない。

追加の関連回帰で並列SQLite報告の接続競合（SQLITE_BUSY）を検出した。使用量更新と確認読み取りを同じトランザクションにまとめ、Goal本体とcriteriaの読み取りにも同じ接続を使うよう修正した。親・子の報告が永続Goalに二重計上されず、独立Run上限より先にGoal上限へ達して停止する試験も追加した。

## Git

独立ブランチcodex/persistent-goal-token-limit。feature Commit/Push→main Merge→Merge後関連テスト→main Pushの順に進め、検証済みhashは後続の作業記録へ保存する。

## 適用範囲

報告後の停止条件であり、開始済み並列呼出しの請求上限を保証しない。未報告の事前予約は、実際に送信したか不明でも保守的に停止対象とする。詳細は[persistent-goal-token-limit.md](persistent-goal-token-limit.md)。実LLM・有料Providerのusage品質を検証したとはしない。
