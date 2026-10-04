# A2 複数ファイル Goal 条件 実装レポート

単一ファイルの完了条件を、最大16ファイルの全件SHA-256一致へ拡張した。SQLite条件テーブルを追加し、既存Goalは単一条件として読み込む。保存は既存Goal/historyと同一トランザクションで行う。

Shell `--criteria-json`、既存Planning Loop/FIFOへの全条件提示、独立検証、Reflectionへの全期待条件保存を統合した。Project境界、Run上限、LLM事前予約を再利用した。

検証: 未実装APIでのコンパイル失敗を確認してから実装。全体 `-Pfull test` は2,840 tests / 545 suites、failure 0 / error 0。追加6テストは再読み込み、部分一致・変更検出、重複/上限/親参照拒否、旧Goal/予算互換、実際のShell・FIFO・2回継続、JSON入力拒否、Reflection保存を確認した。`git diff --check` 成功。

汎用コマンド条件・Git条件・意味判定・不確定Run復旧はDeferred。複数条件のReflectionでは同じ順序のJSON配列をexpectedFile / expectedSha256に記録する。
