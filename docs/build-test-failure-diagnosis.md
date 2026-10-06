# Build/Test Failure Diagnosis

runCommand結果と管理processのsnapshot（status/wait/kill/dependency wait）にdiagnosisを追加した。
既存の状態・exit・stdout/stderrから算出し、command/LLM/修正/retryは追加実行しない。
既存constructorを維持し、新canonical constructorでも診断値は観測フィールドから再計算する。

outcomeはRUNNING/SUCCEEDED/FAILED/TIMED_OUT/CANCELLED/UNKNOWN。
categoryはTEST_FAILURE/COMPILATION_FAILURE/DEPENDENCY_FAILURE/EXCEPTION_REPORTED/NONE/UNKNOWN。
exit=0の例外ログはSUCCEEDEDを変えず、負例テストの可能性をwarningsで示す。
実行中は暫定、未取得processはUNKNOWN、非0exitだけでは原因を推測しない。

Maven/Surefireの個別失敗と集計、Gradleの個別FAILED、javac/MavenのJavaコンパイル診断、
既知の依存解決失敗、Java例外名とCaused by行を認識する。
代表categoryはテスト→コンパイル→依存→例外の順で選び、引用は別途保持する。
failedTestsは報告された名前、causesは報告されたCause行で、独立検証した根本原因ではない。
evidenceはstdout/stderr/messageの出典・種別・引用を持つ。nextActionsは固定の確認手順であり、
ログ内の指示やcommandを実行せず、権限を追加しない。

診断対象はsourceごと64KiB・2000行まで。巨大sourceは解析せず、完全ログの確認を促す。
8192文字超の行は除外、引用512文字、evidence24件、failedTests12件、causes最新8件まで。
既存CredentialRedactorでマスキングしてから引用を切り詰め、未閉鎖private key blockも除去する。
省略時はpartial/warningsを返す。管理processの対象は既存の直近ログで全量ではない。
raw stdout/stderrと保存は既存の仕組みを使い、診断用の別DB/cacheは作らない。
Tool結果のcontext圧縮ではdiagnosisをverbose stdoutより先に取り込む。

保存済みJUnit XMLの読取は後続の[JUnitレポート診断](junit-report-diagnosis.md)で対応した。
対象外: その他のtest report形式、多言語診断、完全な原因特定、診断専用UI、自動修復/retry。
TDDで未実装compile失敗を確認後、報告test/cause/source、compiler/dependency/unknown、
timeout/run/cancel、認証情報除去・上限・負例ログ・JSON出力を検証する。
実Java子processの終了ログ、Tool context圧縮での診断保持も検証する。

関連テストで既存process lifecycleテストの非同期Event配信待ち競合を再現し、thread-safeな受信listと上限5秒の配信待ちへ修正した。

全体回帰: full profile 2817 tests / 541 suites、failure/error/skip 0。
