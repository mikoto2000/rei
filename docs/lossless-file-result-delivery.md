# ファイル本文のロスレス配送とレビュー完全性

## 調査の基点と変更理由

基点は origin/main `407ca34093556c2bea0290a5e6ee40c1ff21ed87`。既存の通常Chat/Agent Runは `StagnationChatModel` → `ToolLoopSupport` → Spring AIのToolCallingManager → ToolResponseMessage → `ContextAssembler` の投影 → モデル送信、という順序だった。`ToolResultCompressor` は原文を保存して重要行・先頭・末尾を返していた。この診断ログ向けの処理を本文にも適用すると、中間本文が投影から失われる。`readMultiFile` 自体の1000行・バイト上限や expectedVersion/nextRead は別に存在する。

ローカルSubAgentは `SubAgentToolCatalog` と `SubAgentRunner.guarded` → `BoundedToolLoop`。外部Codex/Claude Codeは `ExternalAgentDelegationService` と各CLIバックエンドで、外部内部のモデル入力は観測できない。ツール結果取得、モデルへの配送、構造化レビューの検証を独立の境界にした。

## 配送ポリシー

ローカルの `@ResultDelivery` を `DeliveryToolRegistration` が登録時に解釈し、型付きメタデータを生成する。JSONの自己申告やツール名の部分一致で権限やポリシーを変更しない。イベント・進捗・StableToolDefinitions・SubAgentのラッパーでも属性を保持する。

| 種類 | 配送 |
|---|---|
| FILE_CONTENT | 連続した本文ページ。中間抜粋なし。元の全結果をraw参照で回収可能 |
| LOSSLESS_RESULT | 委譲結果のJSONを連続ページで配送。指摘・失敗理由・各子の結果を抜粋せず、元の全結果をraw参照で回収可能 |
| RAW_RESULT_PAGE | 再抜粋・再保存しない。予算不足時は原文から連続ページを縮小 |
| SEARCH_RESULT / GENERAL / DIAGNOSTIC_LOG | 既存の重要行・先頭・末尾方式とraw参照。本文ツールと混同しない |

FILE_CONTENTの対象は readMultiFile、searchAndRead、readSkillResource、readTextChangeSetBase、readPdfFile、readReviewFile。検索の候補・matchesと本文sectionsは既存の構造を保つ。delegateTask / delegateTasks は LOSSLESS_RESULT として登録し、動的なツール定義・遅延callback・イベント・安定化ラッパーでも分類を保持する。委譲結果はファイルの配送・レビュー完了を証明するreceiptにはしない。

大きい委譲結果は `rawResultRef` / `content` / `nextOffset` / `truncated` を持つページになる。`content` は元の結果JSONの連続した断片であり、単独の完全な結果JSONとは限らない。`truncated=true` なら `readRawToolResult(rawResultRef, offset=nextOffset, limit=16000)` を呼び、`hasMore=false` まで次の `nextOffset` を辿る。各本文を順番に連結すれば、単一・バッチとも元JSONと一致する。小さい結果は既存の結果JSONのまま返す。会話要約を無効にしても、この配送と最終入力上限の調整は有効。

`readRawToolResult` の入力名は `offset` です。前回の応答が `nextOffset: 3433` なら、次は `{"rawResultRef":"同じ参照","offset":3433,"limit":16000}` と指定します。`nextOffset` を入力に使ったり、数値を `"3433"` のように文字列で渡したりしません。必須引数・型・数値範囲の不正は、保存結果を読む前にまとめて検出し、既存の回数制限付き引数修正ループで LLM に返します。この修正応答は原文ページと区別して保存・圧縮し、承認待ちから続行する場合もその区別を保持します。

一般ログの GENERAL / DIAGNOSTIC_LOG / SEARCH_RESULT では重要行・先頭・末尾の表示が引き続き残る。例えば runCommand の大きいログはexitCode/error/status/stderr/diagnosisを優先し、完全ログはraw参照で取得する。本文と委譲結果にはこの抜粋方式を適用しない。登録されたレビューPage以外の文字列から配送receiptを作らない。

## ページ分割と予算

モデル送信前の全メッセージを評価し、実効上限は既存のモデル別context limit、completion reserve、tool reserve、安全余白、hard limitの小さい方を使う。既定値はモデル128000、completion8192、tool4096、安全4000、hard85000、圧縮閾値70000、recent12000、summary3000。会話圧縮の有効/無効にかかわらず最終入力上限を確認する。既存のRun/Goal、キャンセル、停滞、最大ステップ、再試行上限は維持する。SubAgentの補助要約・意味検証も親のモデル呼出し予算と入力上限を使用する。

JTokkitのCL100K/O200Kと日本語・UTF-8の保守的見積りの最大を取り、JSONのescapingも評価する。未知のモデルの厳密なtokenizerではないので、安全余白を維持する。ソース本文だけでなくパス、version、tool ID、cursor、JSONフィールドを含めて見積る。

UTF-16の文字オフセットとcode point境界を使い、二分探索で収まる最長の連続prefixを選ぶ。末尾だけを縮め、サロゲートペアを分断しない。readMultiFileは既存の行番号・offset・charset・expectedVersionのnextReadを保つ。複数ファイルは全結果JSONを測定する。大きなその他のFILE結果は元JSON文字列のrawResultRef/offset/nextOffsetで欠落なく回収できる。rawページはヘッダ7行とその後の原文本文で、nextOffsetとhasMoreを持つ。ヘッダの改行などはエスケープする。

readPdfFileのモデル用APIは任意のpage/offset/expectedVersion/tokenBudgetを追加した。pageは1始まり、nextPage=0は末尾。PDFBoxの抽出テキストをページ単位で返し、ページ番号・総ページ数・原PDFのハッシュを保持する。既存のJavaの1引数本文抽出メソッドは維持する。新しい版で続行しない。画像・表の視覚内容とOCRは対象外で、テキスト抽出の網羅性を視覚レビューの完全性と呼ばない。

## レビューのプロトコル

Project Runで `beginReview(paths,criteria,criteriaVersion)` を使って対象と不変の判定基準を確定する。単なるファイル読取をレビュー済みと扱わない。全体レビューには明示的なJobが必要で、自然言語から任意のレビュー対象を推測・自動確定しない。

1. `readReviewFile` が exact source/version/range/contentHashを返すとFETCHED。
2. 実際に投影・送信した完全なPageのハッシュと信頼されたreceiptが一致し、モデル呼出しが正常終了した場合だけDELIVERED。不明・失敗・キャンセルした送信は確定しない。
3. `submitReviewResult` の範囲と版が配送済みの記録に一致し、現在ソースのハッシュ・根拠文字列・行/文字座標を検証するとREVIEWED。指摘なしでもassessmentが必要。
4. 全requiredRangesの区間和を覆い、未配送・未レビュー・失効・失敗がない場合にCOMPLETE。空ファイルも明示的な空範囲の配送・レビューが必要。

`REVIEWED ⊆ DELIVERED ⊆ FETCHED`。取得の成功、自然言語の完了宣言、外部プロセスのexit code=0では完了しない。通常ループ/子Runの自然終了にも未完JobがあればREVIEW_PARTIALで停止する。レビューreceipt付きPageは一つの証拠として原子的に配送する。最終予算に入らなければ上限停止となるので、より小さいtokenBudgetで再取得する。半分のPageを全範囲配送と記録しない。

`reviewStatus` は現在versionを再確認し、削除・読取不能もSTALE_VERSIONにする。`reviewPending` は未レビュー区間と次の文字位置を返す。`restartReview` は同じ対象一覧を現在版で再構築し、旧範囲と旧指摘を失効状態で保持する。UTF-8、BOM付きUTF-16、既存CP932の厳密デコードに対応し、レビュー用文字列のBOM/CRLFも保持する。PDFを明示レビューする場合、各抽出ページ間をform feedで区切った論理テキストを対象にする。PDFの元ページ位置はreadPdfFileで確認できる。

## 永続化、再開、進捗

SQLiteのreview_jobsはJob、所有Scope(project/conversation/run)、参加Scope、不変criteria、対象path/version/lineCount/requiredRanges、range/hash/状態、構造化Findingを保存する。本文は台帳に複製しない。review_participantsの索引で所有スコープのJobだけを取得する。

Findingはid/severity/path/version/location/evidence/assessment/verificationStatusを保持し、同じ位置・証拠・評価の重複を抑制する。意味的に正しいかを証明するものではない。同範囲の再取得・再評価は区間の新規進捗にならない。実際に新しく検証されたレビュー区間と指摘は状態進捗になる。既存の有限予算と停滞制御を迂回しない。

Checkpointは既存形式のEvidenceにREVIEW_JOB_REFERENCEだけを追加する。ソース版・所有権を照合し、同じProject/conversationの検証済みResumeにだけ新Run参加権を与える。古いCheckpoint・旧raw3フィールドJSONも読める。履歴の要約や削除は台帳を消さない。別Project/conversationの所有権を推測して復元しない。

ローカルSubAgentは明示的にroleで要求したREAD能力だけを付与する。レビュー/rawツールは監査済みallowlistに追加し、NETWORK_READやMCP・再委譲の境界は変えない。証跡ラッパーは非切詰めと内部SHA-256を確認してからPageを復元する。子の終了時に既存の親子関係と同じProjectを確認して親をJob参加者にする。子の失敗/取消は未完状態を残す。

外部レビューは既存の制限付きsource snapshotから対象manifestと原ソースの版・範囲を親側に保存する。manifestの取得不能は未確定対象として保持する。外部出力・根拠は既存の履歴/評価保存に残し、警告にreviewJobIdとREVIEW_UNVERIFIEDを付ける。外部内部の配送証跡が得られないので、外部の成功をREVIEWEDやCOMPLETEに変換しない。取消はREVIEW_CANCELLED。

## 設定と容量

application.yamlのrei.tool-result-delivery:

| 設定 | 既定値 |
|---|---:|
| page-target-tokens | 8000 |
| page-min-tokens | 256 |
| page-max-tokens | 16000 |
| bulk-max-tokens | 64000（全文取得でも入力残量以下） |
| page-safety-tokens | 0（既存context安全4000に追加する余白） |
| raw-result-max-bytes | 16777216 |
| raw-store-max-bytes | 268435456 |
| raw-cache-max-bytes | 8388608 |
| review-ledger.enabled | true |
| review-ledger.require-coverage | true |

台帳を無効化しても本文配送と最終上限保護は残る。台帳容量は64MiB、Job2MiB、conversationあたり128Job、64対象、10000範囲、2000指摘。既存ファイルsnapshotは1MiB/ファイル、8MiB/要求。大きすぎる対象は明示的に狭める必要がある。

raw本文はconversation内の内容SHA-256で共有し、ツール名/call IDを持つ独立pointerを作る。異なるconversationの本文は共有しない。captured Run/Project/root/mode全体をcache keyにし、8MiB・128件・10分TTLに制限する。読取時にpointer/bodyの利用可能性を再確認し、キャッシュのない本文はハッシュも検証する。子Run終了や明示clearRunで消去する。sourceが変化した読取は古い版の進捗に合算しない。

原文pointerとbodyのレジストリを1トランザクションで登録し、reference edgeで既存の保守的な保存期間/削除処理と整合させる。rawページを別原文として再保存しない。容量超過を黙って削除・緩和しない。台帳はJob単位JSONのため更新は範囲数に比例する。上限とparticipant索引・一括DELIVERED更新・重複無更新で制御しており、無制限の大規模レビュー向けではない。

## エラーと移行

CONTEXT_BUDGET_INSUFFICIENT/CONTEXT_HARD_LIMITは最小ページや固定入力が入らない場合。INVALID_CURSORは範囲外/サロゲート分断。STALE_VERSIONはソース変化。RAW_RESULT_NOT_FOUNDは原文不在、RAW_RESULT_ACCESS_DENIEDはcaptured conversation外、REVIEW_RESULT_INVALIDは未配送/偽装/版・根拠・座標不一致。REVIEW_PARTIAL、REVIEW_UNVERIFIED、REVIEW_CANCELLED、FAILEDは台帳や既存の停止状態として残る。送信が不確定ならDELIVEREDに昇格しない。

起動時StorageMigrationCoordinatorの既存バックアップ/排他/トランザクションを使用する。schema9でreview_jobs、10でparticipant索引を追加し、9のJob JSONから参加者をバックフィルする。DDL不整合・新しすぎるschemaは停止する。既存データを消去したりIF NOT EXISTSで不整合を隠したりしない。DotMigrationTestの旧schema fixtureは9/10の表とmigration markerも除去して実際のschema7を再現し、現行SCHEMA_VERSIONを厳密に検証する。

## 評価の実行

通常の固定テスト:

```powershell
./mvnw.cmd -o '-Dtest=ReviewQualityEvaluationTest,RawPagingScaleTest' test
```

フィクスチャはsrc/test/resources/review-evaluation、正解位置はgold.json。中間誤り5件を日本語Markdown/Java/複数教材/長い1行に配置した。固定応答モデルはCIの配送・根拠検証用oracleであり、実モデルの品質を示す数値ではない。target/review-evaluation.jsonに検出数、誤検出数、網羅率、保守的入力tokens、モデル/ツール呼出し、時間、raw本文読書回数、保存ファイル総bytesを出力する。保存総bytesはOSの累積物理書込量ではない。

実LLMは通常CIから独立し、明示的に以下を設定して実行する。APIキーは環境変数のみで渡し、ログに出さない。

```powershell
$env:REI_REVIEW_EVAL_LIVE='true'
$env:REI_REVIEW_EVAL_URL='https://your-approved-endpoint/v1/chat/completions'
$env:REI_REVIEW_EVAL_MODEL='your-model'
# REI_REVIEW_EVAL_API_KEYを安全な環境から設定
./mvnw.cmd '-Dtest=ReviewQualityLiveTest' '-Dtest.excludedGroups=' test
```

最大256モデル呼出し、各HTTP60秒・出力4096token、再試行なし。構造化出力・範囲・根拠検証は固定評価と共通。検出率はdetected/expected、誤検出は非正解位置への指摘数。位置の一致は意味的な正しさの完全な保証ではない。無効出力はテスト失敗として残す。今回の実装中に実LLMへ接続する必要はなく、実行していない。

全Java検証は ./mvnw.cmd -o -Pintegration test（liveを除外）。UIの契約は変更していない。既存JDK25のMockito/SQLite警告は配送機能の失敗とは区別する。

WindowsのプロセスI/O・メモリの測定は `./scripts/measure-review-evaluation.ps1`。Maven/Javaの所有process treeだけを計測し、target/review-process-io.jsonに読書bytes/操作数・観測最大working setを出力する。SQLiteのnative I/Oも含むが、コンパイルやpipe出力を含むsampled lower boundであり、物理ディスクの厳密な累積書込量とは異なる。同じウォーム状態で比較し、OS単位の精密評価には別途ETW等を使う。liveネットワーク評価はこの性能スクリプトから起動しない。

## TDDと回帰記録

Phase 1: lossless pager / disabled compression / trusted delivery / raw-page安全性 / final remainderのテストを先に失敗させ、連続prefix・明示cursor・最終上限保護を実装。
Phase 2: FETCHED/DELIVERED/REVIEWEDの分離、所有権、版失効、実ToolCallingManager境界を先にテストし、永続台帳・receipt・Checkpoint参照を実装。
Phase 3: raw body重複・Run内cache、native長行cursor、model transport失敗、外部opaque状態、欠落継続位置、PDFページのテストから実装。さらに偽装した指摘座標が受理されるRed、保存登録失敗後にファイルが残るRed、native nextReadがraw wrapperになるRedを確認し、拒否/後始末/上限内schema保持をGreenにした。

4k以下の信頼された本文結果はnative schemaを保ち、さらに大きな結果を2k目標のraw prefixにする。子のnative producerは2k上限として証跡wrapperの余白を確保する。レビューPageは専用receiptを保つ。exact final budgetが足りなければ安全なraw縮小または明示停止となる。

Windowsの全体結合テスト中、ExternalAgentProcessRunnerのProcessHandle.parentネイティブ列挙で進捗停止を観測した。既存にも循環検出はあったが、その前のnative親取得で停止していた。Windowsでは既存JNA依存の一回のToolhelp snapshotから親を列挙し、64祖先・65536process・1秒で制限、失敗時には所有権が不明な子を終了しない。元のcreation time/root生存照合は維持した。循環/欠落/深過ぎるスナップショットと、停止していたSpecificationDelegationIntegrationTestを再検証する。

全Java回帰の旧schema2/3 fixtureも、Dotのschema7 fixtureと同様、現在schemaからダウングレードを再現する際にreview_participants/review_jobsを除去する。rollback後のuser_version、原JSON不変更、イベント/cursorのexactly-once、子process保持中の排他という元のassertionは維持した。IF NOT EXISTSで本番移行を通す対応はしていない。

完全性の保証対象はbeginReviewで明示的に確定したJobである。任意の自然言語から対象を推測して登録しない。レビュー用roleにはbeginReview/readReviewFile/submitReviewResult/reviewStatus/reviewPending/restartReviewを明示して要求し、通常のreadMultiFileのroleにはnative nextReadの続行を使う。必要な範囲が予算に収まらない場合は部分完了で停止し、検証済みCheckpointから再開する。

巨大な原文がraw-cache-max-bytesに入らない場合は本文の再読込が発生する。通常のnative producerは予算内にページ化するためこの経路を避ける。大きな一般ログを反復回収する運用では、原文上限とcache上限を合わせて調整する。保存データの容量上限を無効にして最適化しない。

32768文字を超える入力はBPEの巨大単一語マージを回避し、JSON escaping後のUTF-8 byte数を上界として使う。短いページは従来どおり両tokenizerと保守的見積りを比較する。これは大きな入力を過大評価して保留することがあるが、計算量とモデル入力上限を守るための調整である。巨大語のRed/Greenを追加した。Windowsの大小文字aliasはcanonicalファイル名で一つの対象に正規化し、指摘の根拠位置もサロゲート境界を検証する。行/文字位置はUTF-16単位で、末尾改行後の空行位置も含む。

最終ローカル検証（コード904ecd31、main05847e5a統合後）: CIと同じ .github/ci/full-test.ps1 の-Pfullで5409件、失敗0、エラー0、スキップ5、12分21秒。REI_TEST_WINDOWS_CODEX未設定2件、REI_TEST_WSL_CODEX未設定1件、実PlantUML renderer条件1件、rei.io.benchmark未設定1件を除く。liveタグの実LLM評価は通常CIから独立し、今回は未接続。固定応答・process I/OのJSONと検証概要はdocs/lossless-file-delivery-data/に保存した。既存Native Clientのコード/API契約は今回変更していない。

## 原結果ページの保存形式

`readRawToolResult` は専用の結果 converter で改行を含むテキストをそのまま返す。既定の JSON converter が String 全体を引用すると、チェックポイント保存側が `rawResultRef` のヘッダーを検出できず、成功したツール呼び出しの後で停止するためである。

保存と最終入力調整は共通の `RawResultPage` parser を使う。旧形式の JSON 文字列ラップは一度だけ解除し、参照・cursor・本文長・継続状態を検証する。本文中の `rawResultRef` や任意の結果 JSON は出所として採用しない。予算調整でページを再生成する際も、元の要求範囲より広げない。チェックポイントにはページを新しい原文として保存せず、元結果の参照を保持する。不正な形式は `raw_page_invalid_provenance`、元結果を参照できない場合は `raw_page_source_unavailable` として対処方法を表示する。
