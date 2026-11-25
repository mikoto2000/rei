# Phase 9: 隔離した実アプリケーションの受入記録

更新日: 2026-11-25。**Phase9工程完了**。実試験、fixture、FAILED/未検証を区別する。
PR [#637](https://github.com/mikoto2000/rei/pull/637)、通常merge `18fb7d27ef0c98f337ad3be9647ddb0dd364ba2c`。
PR/マージ後exact SHAのJava・静的解析・Client CIは全成功、8316件/成功8304/期待skip12/failure・error0。
専用worktreeとlocal/remote branchは削除済み。ローカル結合調査2試行のFAILED判定は維持する。
本番プロセス・JAR・外部設定・DB は変更しておらず、本番 Responses 生成 gate は閉じたまま。

## 対象・承認・判定基準

対象は OpenAI `https://api.openai.com/v1` / `gpt-5.6-sol` と、利用者指定の
`http://gx10-707e.local:8888/v1` / `deepseek-v4.1-flash-1017`。
後者を DeepSeek 公式 API の試験結果に読み替えない。Spring AI 2.1.0 / OpenAI SDK 4.101.0 を維持する。

承認済み追加枠の **累積** 各200要求・入力100万/出力30万トークン・OpenAI USD20・ローカル課金なしを維持する。
開始は09:27:42 JST、当初3時間の期限12:27:42で呼出しを停止し、利用者の明示承認後に
時間のみ2時間延長した。終了は本日14:27:42 JST。元の開始時刻・予約・使用量・上限をリセットしない。
初期試験7要求ずつは別枠であり、今回の台帳へ移し替えない。送信は合成データと所有ツール定義のみ。

今回の候補 snapshot は構成時に固定する。

| 項目 | 条件 |
| --- | --- |
| API / replay | Responses / OpenAI encrypted、指定ローカル plaintext |
| max output / max tools | 2048 / 36 |
| 宣言する optional settings | REASONING_EFFORT のみ |
| effort / tier | low固定、OpenAI default固定、ローカルへtierを追加しない |
| timeout / SDK retries | 120秒 / 0。全体承認期限の残り時間でも制限 |
| 明示用途 | chat、output-limit-planner。外部設定からの本番選択ではない |

Shortは3 READ/WRITE対=6操作、Mediumは10対=20操作、Longは25対=50操作。
RunのLLM上限はそれぞれ8/27/64要求（要約も含む）。既存 ToolLoop が全iterationを実行する。
成功条件は操作回数・順序・ファイル内容・計算結果・最初の識別情報の完全一致、Longでは実圧縮2回以上。
結果を見た後でこの条件を緩めない。失敗した作業領域を削除して再試験しない。

## 隔離構成

`SpringApplicationBuilder(ReiApplication.class, 明示TestConfiguration)` で実際の Spring アプリを起動する。
検証領域内の最小設定のみを指定し、利用者の `application.yaml` や classpath の既定設定を取り込まない。
子JVMの環境は明示選択した鍵と必要なOS変数だけ、home/tmp/data/log/DBも所有領域内とする。
WebはNONE、MCP/Slack/voice/secretary等を停止し、scheduled annotation processorをsingleton作成前に除去する。
FullAuto無効・権限有効・スケジューラー0・所有data-dirを起動時に検証する。

実際の Spring `ChatExecutionService`、provider、payload store、Checkpoint、権限、承認、Session、ProjectService、
ReviewTools、ReadingDelivery、WorkingSetを使用する。Webを起動しない構成ではRunRegistry/RunServiceが登録されないため、
Web構成と同じ既存コンストラクター・実DataSource・event bus・cancellation/routerで検証用に明示登録する。
ChatClientのツール公開とモデルの計測装飾は検証用構成で差し替えるため、stock advisor全体を無変更で検証したとは扱わない。
既存RunAware advisor / Stagnation / ToolLoop / 実LlmConversationCompressorを経由し、別agent loopを作らない。

READは所有する数値・証明鎖・識別情報を返す`now` fixture。WRITEは既存`Tools.writeMultiFile`への限定callbackで、
所有する進捗ファイル1個のpath/content/順序を検査する。実ApprovalRepositoryで各owner・各内容の有限個のJSON表現を
事前承認し、既存guardが一度だけconsumeする。LOCAL_WRITEをREADへ分類し直さず、広い自動承認を追加しない。
各WRITEが未知の次READ証明を返すため、正しい後続操作を並列に先行生成できない。

## 実測結果

以下は実試験の記録（後半の追記と併読）。各セルの要求数にはそのRunの実要約を含む。異なるモデル・試行・版の数値を単純比較しない。

| 試験 | OpenAI | 指定ローカル |
| --- | --- | --- |
| 実アプリSmoke | 成功、1要求 | 成功、1要求 |
| Short / 6操作 | 成功、7要求、25.861秒 | 成功、7要求、37.932秒 |
| 最初のMedium / 20操作 | **失敗**、23要求。20操作・合計値は正しいが圧縮後の最初の識別情報が欠落 | **失敗**、24要求。要約本文のトークン誤計上で圧縮が拒否され、後に出力上限で終了 |
| 修正後Medium / 20操作 | 成功、23要求、65.049秒、実圧縮2回、識別情報保持 | 成功、24要求、208.554秒、実圧縮3回、識別情報保持 |
| Long / 50操作 | 成功、58要求、177.411秒、実圧縮7回、識別情報・合計値・順序一致 | 成功、58要求、493.165秒、実圧縮6回、識別情報・合計値・順序一致 |
| Goal / Planning / SubAgent / cancel | 下記追記参照 | 下記追記参照 |

OpenAI Longの圧縮では対象履歴推定3599→239、3096→234、3065→240、3104→240、
3110→244、3080→240、3100→238トークン。through sequenceは単調増加し、直前の新しい結果を保持して継続した。
Run累積payloadは117,545 bytes、JVM heapは開始180,503,136 / 終了274,075,640 bytes（peakではない）。
store全体のpayload237,798 bytes / 227件 / references28,933件には、この接続先の過去の失敗・成功Runの保持分も含む。
これらの全store値を単独Longの値やリークの有無へ読み替えない。
Long終了時の追加枠累積は177要求・入力270,507/出力12,437、保守的標準料金上限見積りUSD3.078180、実請求は未確認。
小標本から品質向上、統計的優位、全モデル・全機能の能力を認定しない。

## 実試験で再現した修正

1. 要約器が既存のAPI中立`ModelTokenUsage`を参照せず、nativeの推論分を本文の制限へ含めていた。
   ローカル実測の総出力1418・推論953・本文465が、本文1418と判定されていた。
   集約前のbackend数値を保持し、既知の推論分だけ本文から分離する。Runには引き続き総トークンを課す。
   reasoning欠損は保守的に総出力で判定する。業務要約器へ新しいベンダー固有分岐を追加しない。
2. 圧縮対象prefixの外に残る現在タスクが、要約の事実選択へ渡っていなかった。
   実RunのuserRequestを命令としてではなくuntrusted selection contextとして渡す。
   最大1024 UTF-8 bytes、長い依頼はUnicode境界を保った先頭/末尾と省略印に限定し、既存2048-tokenのprompt余裕内に収める。
   元payloadへ識別情報を書き戻したり、結果から正解を補完する処理は追加しない。

Red `e8e73a749` は4境界中3 assertion failure・error0（465を期待して1418、現在タスクの欠落2条件）。
Green `9f7369b55` 後、要約/予算/native options関連79件と、native replay169件+実アプリfixture1件が全成功。
前後の実Mediumは同じ合格基準で別領域へ保存し、最初の失敗を成功で上書きしない。

試験ハーネスでもgetOutputLimitの防御コピーへの変更、固定Session metadataの衝突をRed/Greenで修正した。
単独JVMのDevTools再起動、Web専用Beanの不足、ProjectClientScopeの配線は起動構成の問題として記録し、製品API不具合と混同しない。
起動失敗中に実API要求は発生していない。終了コードだけでなく、その起動以降の新しい完了receiptも要求する。

## 証拠・残作業

所有記録は`F:/project/rei/target/responses-api-workflows/phase7-10-20261125/`。
元の`extended-live/<target>/budget.jsonl`へ時間延長Eを1回だけ追記する。
H・累積枠・不明usage予約は維持し、子プロセスの壁時計期限も元開始+承認時間で制限する。
raw reasoningは所有SQLiteだけに保持し、報告は長さ/hash/件数/固定診断/数値に限定する。
起動時のclass hash・Git head/dirty pathsも保存する。キーや一般環境変数を記録しない。

Phase7の実承認/拒否・別JVM CheckpointとPhase6の異常終了/GC/120round/外国owner等のfixtureは補完証拠であり、
今回の新しい候補profileで全ケースを再実施したことにはしない。
以下にPR準備時からの追記を残す。最終の全品質・全回帰・通常merge・exact SHA CI・証拠保存・cleanupは完了している。

## 最終実試験の追記

| ケース | OpenAI | 指定ローカル |
| --- | --- | --- |
| Goal | PASSED、2要求、実Goal COMPLETED・3成果物/hash一致 | PASSED、同条件 |
| Planning component | 初回4subgoalsでFAILED。明示3項目の別タスクでPASSED、各1要求 | 同経過、各1要求 |
| SubAgent | 初回親3要求上限でFAILED。事前上限5の別試行は3要求でPASSED | 同経過、3要求でPASSED |
| SSE中cancel | PASSED、1要求、実Run CANCELLED・不明usage予約1件追加保持 | PASSED、同条件 |
| 調査・動的公開・実圧縮 | PASSED、7要求、4ファイル・2指摘・圧縮2回・専門ツール1回 | 初回FAILED、再試行を別領域で実施 |
| TOOL結果保存後halt/別JVM再開 | PASSED、2要求、成功済み効果1回・previousRun一致 | PASSED、同条件 |

Goalは実GoalRepository / FileGoalVerifier / GoalLoopServiceを使い、既存Coreと権限を通じて書いた3ファイルをhash検証した。
Planningは実OutputLimitReplannerと計画parserの単独component試験。Coreの出力枯渇からの全自動回復は既存fixtureで補完する。
初回は条件3に対して4項目。各成果物内に検証を含めて正確に3項目と明記した別タスクで成功した。
4項目の計画自体を製品不具合とは判断しない。

SubAgentは実SubAgentTools / stock runner / permission resolver / durable repositoryを使った。
初回も子の構造化回答・境界・durable COMPLETEDは正しかったが、親の3要求上限内に最終回答がなかった。
別試行は実行前に上限5、local backendとdelegate引数を明記し、同じ成果物条件で成功（消費3要求）。
全体200要求枠は変えない。子の実呼出し1回、別Run/sessionと同じendpoint snapshotのpositive境界を確認した。
private情報のnegative注入はこの実試験では行わず、既存169件のfixtureを補完証拠とする。

cancelはSSE headers観測後、実RunService.cancelを呼んだ。初回もCANCELLEDかつ不明usage予約を保持したが、
検証スレッドの割込フラグで後続awaitが失敗した。終了済み所有JVMの報告/cleanupに限り割込を消費するようハーネスを修正し再試験した。
製品の取消処理は変更しない。pending予約をunknown表示へ含める報告不具合もRed/Greenで修正し、予約や金額自体は変更しない。

調査は4所有ソースfixtureをREADし、実ToolDiscoveryの32→33公開、実圧縮、事実と2指摘の保持を同時に試す。
業務用readMultiFileのReviewCoverage page ledgerはこのケースで検証していない。
ローカル初回は2圧縮と専門ツール検索/追加公開まで成功したが、専門ツールの代わりに既読ページを再要求した。
重複READを拒否して停止し、条件はFAILEDのまま保持した。重複拒否やguardを解除しない。
再試行は同じprompt・8要求上限・同じ判定で、新しい領域だけを指定した。モデルの非決定性を含むため単発成功を信頼性保証にしない。

再開試験は新候補profileの実Core / PersistentCheckpointServiceでTOOL結果保存後に所有JVMをhaltし、別JVMから同じ所有SQLiteで再開した。
保存済みprefix・previousRun・単一効果・COMPLETEDを確認した。実アプリ全体の任意crash点や不明な外部副作用を網羅したとはしない。
Phase7の承認/拒否とfixtureのGC/外国owner拒否を補完証拠とする。

ローカルLongの圧縮対象推定は3654/3700/3425/3390/3441/3292→422/389/398/411/400/344。
Run累積payload126,987 bytes、heap開始182,573,040 / 終了250,353,704 bytes。
store全体249,133 bytes / 227件 / refs28,871には過去Run保持も含む。
途中185.3秒の180標本でCPU15.375秒、working set654,245,888〜712,335,360 bytes、private713,207,808〜770,752,512 bytes。
全Long区間peakではない。OpenAIのCPU/peakは未測定。

当初Phase9計画120要求/targetは不具合再試験等で超過した。明示承認された追加枠全体200要求は据え置き、全失敗/取消も台帳へ算入した。
ローカル品質はCheckstyle0 / SpotBugs0 / analysis error0 / missing classes0。
要約/予算79件、native169+実アプリfixture1件、最新予算/計測/時間延長21件は全成功。
全回帰・PR・exact merge-SHA CI・cleanupの最終結果はPRおよびworktree外の最終報告へ保存する。

### 実API試験終了時の台帳

実API呼出しは14:23までに終了し、以後は追加呼出しを行わない。承認期限14:27:42も変更しない。

| 対象 | 追加枠累積要求 | usage確定 / 不明予約 | 入力計上 | 出力計上 | 費用 |
| --- | --- | --- | --- | --- | --- |
| OpenAI | 198 / 200 | 196 / 2 | 317,560 | 18,276 | 保守的上限USD3.723880、実請求未確認 |
| 指定ローカル | 187 / 200 | 183 / 4 | 462,219 | 49,000 | 利用者確認の課金なし |

トークン計上にはusage不明要求の全予約を含む。初期7要求ずつは別承認枠であり、この表へ移さない。
ローカル調査再試行は7要求で最終JSON parseに失敗したためFAILED。成功扱いに補正しない。
初回の既読再要求と合わせて、圧縮・動的公開・調査の結合で安定した受入条件を満たしていない。
したがって指定ローカルの本番gate解禁は不可と判定する。Phase10で未検証/不合格のまま保護し、OpenAIの判定と独立に扱う。
実試験全体の完了（全項目合格）とは表現せず、失敗・fixture補完・未検証の範囲を引き継ぐ。

### 最終ローカル品質

最終ソースの再コンパイル（main1768 / test1410）と対象75テストは全成功、失敗・エラー・skip0。
最終Checkstyle0 / SpotBugs0 / analysis error0 / missing classes0 / Spotless成功。
18個の所有試験DB（各接続先9個）はすべてPRAGMA quick_check成功。DB/hash/WALサイズと数値台帳は外部`phase9-final-live-summary.json`に保存した。
ローカル全回帰とPR/merge CIの最終件数は後続記録で確定する。

最初のローカル全回帰は、一時領域をGit worktreeの`target`内に置いたため、非Gitディレクトリを前提とする検索系fixtureが親`.git`を検出し、8 failure/1 errorとなった。
所有Mavenだけを停止してログを保存し、コード・期待値を変えずGit外の専用一時領域で該当41件を再実行した。失敗・エラー0、既存skip2。
その配置で全回帰を新規XML領域へ再実行する。試験計画Python10件も成功した（最初のsandbox一時領域PermissionDeniedは所有領域への変更で解消）。

### 全回帰の終了

最終ソースの全回帰は8,316件、成功8,304件、失敗0・エラー0・skip12、25分20秒でBUILD SUCCESS。
基準Phase8の8,304件に対する増分は12件。XMLのテストID・期待skip照合結果とPR/merge CIは外部記録およびPRへ確定値を保存する。

独立XML集計も1355 suites / 8316 tests / 成功8304 / failure0 / error0 / skip12。
期待skipのID・理由は設定と完全一致、新規skip0、基準8304件のテストIDは全保持、追加12件、削除0件。
XMLの最古更新は再実行開始後14:35:04であり、前の部分実行のXMLを成功証拠として混在させていない。
ローカルXMLと静的解析XMLはworktree外へ保存済み。
