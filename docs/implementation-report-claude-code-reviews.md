# Claude Code CLI provider 実装記録

C17の別provider追加として、既存の共通委譲service・Run/Goal予算・SQLite履歴・lifecycle event・Shell/自然言語ToolをClaude Codeへ接続した。`/agent claude review [target]`、provider router、旧row codexを保持するagent列移行、provider別読み取り履歴を追加した。

ユーザーのサブスクリプション利用意図に合わせ、`--bare` 方式から通常認証を維持する `--safe-mode` へ変更した。Claude用の子環境でAPI/cloud overrideを除外し、CLI auth statusのsubscription/OAuthのみを許可する。APIへfallbackしない。CLI Tools/MCPを無効にし、れいが検査・上限付きsnapshotをstdinへ渡す。Codexとの一回claim・共通モデル予算を維持する。

未実装executor/Agent/auth APIのcompile Red→2テストGreen→共有service/provider履歴のcompile Red→Green。既存の履歴障害mockが旧Codex start経路を見ていたため、Codex側の既存経路を維持して回帰を復旧した。履歴read分離のcompile Red→境界Green、古いCLIのbehavior Red→version/probe修正。初回全体回帰3326 tests / 614 suites、failure/error/skip各0。その後、構造化出力の内部処理を外部Toolと混同しない設定のRed→修正と、Shell advisor/設定・Policy統合2テストを追加し、17テスト段階の全体回帰3328 tests / 614 suitesもPASS。最後にprovider取消/例外の固定Codex名が残るbehavior Redを確認して修正し、18新規テストを含む最終全体回帰3329 tests / 614 suites、failure/error/skip各0でPASS。

入力/secret名/UTF-8/binary/サイズ/個数、子環境のAPI除外とOAuth保持、認証拒否・呼出前枯渇、usageのstrict判定、source変化、provider相互排他/履歴旧DB移行、Goal費用の再起動保持、Tool/schema/router・設定・Shell補完を検証する。CLIのhelpに隠れたflagがあるため、help文言の存在を対応証明とせず、保守的baseline versionと実際の固定引数によるhelp probeを使う。

実Claude Code CLIはこの環境のPATHで未確認。実認証・実モデル・サブスクリプション枠を消費するレビューを行っていない。mock process outputと実SQLiteによる制御フロー検証であり、live provider品質や実利用枠内の動作保証ではない。利用する環境でnative CLI installation/loginが必要。

[CLI利用方法・subscription・metadata/source境界・制限](claude-code-reviews.md)。Claudeの直接編集・native session resume・fix proposal・Claude並列実行は本provider追加の対象外。

## Git と最終確認

feature `1d90dfe2` を `codex/claude-code-reviews` にCommit/Pushし、mainへ `cc427165` でMerge。Merge後のClaude/Codex・履歴・予算・設定関連テストはPASS。Push時にremote mainのPR #39（Shell時刻表示、`948534b7`）を検出し、変更を保持して `0e276e3d` で統合した。Claude/Codex関連と時刻表示の4クラスを合わせた統合後回帰もPASSし、mainへPush済み。全体回帰3329 / 614はremote時刻表示統合前、統合後は影響範囲の関連回帰を実行した。実CLI/有料モデルは未実行。
