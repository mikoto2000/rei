# Responses API 実装進捗

更新日: 2026-11-25。完了判定はGit・PR・CI・実装・検証結果と照合する。

| Phase | 状態 | ブランチ / worktree | PR | マージコミット | 検証 |
| --- | --- | --- | --- | --- | --- |
| 0 | MERGED | codex/responses-api-phase-0-foundation / 削除済み | [#628](https://github.com/mikoto2000/rei/pull/628) | d0e3626f9565035cab20cc045dd3ba070985d3fe | PR・マージ後CI成功。全件7,718、成功7,706、skip12、failure/error0 |
| 1 | MERGED | codex/responses-api-phase-1-transport / 削除済み | [#629](https://github.com/mikoto2000/rei/pull/629) | 10542db848d9317c7eaaab06869e63e7ab571d5b | 最終CI全件7,818、成功7,806、期待skip12、failure/error0。品質ゲート成功 |
| 2 | MERGED | codex/responses-api-phase-2-openai-replay / 削除済み | [#630](https://github.com/mikoto2000/rei/pull/630) | a95593f48d0b4f760091d79eaf2cbc411e9a2f49 | PR・マージ後CI7,861件、成功7,849、期待skip12、failure/error0。関連859件・全品質ゲート成功 |
| 3 | MERGED | codex/responses-api-phase-3-message-persistence / 削除済み | [#631](https://github.com/mikoto2000/rei/pull/631) | b22cf6646ae58a1efea325877562d7becd3aea51 | PR・マージ後CI7,960件、成功7,948、期待skip12、failure/error0。関連570件・全品質ゲート成功 |
| 4 | MERGED | codex/responses-api-phase-4-agent-integration / 削除済み | [#632](https://github.com/mikoto2000/rei/pull/632) | 2c141910cc251081d46bd391be23587ce575b3d3 | PR・マージ後CI8,185件、成功8,173、期待skip12、failure/error0。全品質ゲート成功 |
| 5 | MERGED | codex/responses-api-phase-5-deepseek / 削除済み | [#633](https://github.com/mikoto2000/rei/pull/633) | f9fd5a5ebbd4fff2fe65781373d9af4f31027276 | PR・マージ後CI8,215件、成功8,203、期待skip12、failure/error0。全品質ゲート成功 |
| 6 | MERGED | codex/responses-api-phase-6-operational-validation / 削除済み | [#634](https://github.com/mikoto2000/rei/pull/634) | 38a13f1d38f0759049290e37df40cdd078a58e54 | exact SHAのJava/品質/Client CI成功。全回帰8,273件、成功8,261・期待skip12・failure/error0 |
| 7 | IN_PROGRESS | codex/responses-api-phase-7-openai-validation / .worktrees/responses-api-phase-7 | [#635](https://github.com/mikoto2000/rei/pull/635) | 未マージ | 実OpenAI初期7＋追加55要求。Core・推論再送・承認/拒否・Checkpoint・画像/構造化・3試行成功。関連370件成功。最新全CI待ち |

## Phase 7〜10 開始時の状態確認（2026-11-25）

最新の実接続到達点は [Phase7受入記録](responses-api-phase7-acceptance.md) を参照。
OpenAI `gpt-5.6-sol` と指定ローカル `deepseek-v4.1-flash-1017` に各7要求を実施した。
通常/stream、別JVM・SQLite、Function Calling と推論再送が成功。OpenAI JSON Schemaも成功。
ローカルJSON SchemaはHTTP400で拒否されており成功扱いしない。フェーズ全体の受入と本番gate解禁は未完了。

上表は各実装時点の記録。Phase 6 は実 Git と GitHub で通常 merge
`38a13f1d38f0759049290e37df40cdd078a58e54` を再確認した。
exact SHA の [Java](https://github.com/mikoto2000/rei/actions/runs/45492903619)、
[静的解析](https://github.com/mikoto2000/rei/actions/runs/45492904174)、
[Client](https://github.com/mikoto2000/rei/actions/runs/45492904245) は全て completed / success。
Phase 6 の完了を上表の古い IN_PROGRESS 表示だけで判定しない。

Phase 7 専用 worktree を最新 origin/main から作成し、隔離Core・耐久予算・受入試験台帳を実装した。
詳細は [実接続受入試験](responses-api-live-validation.md)。追加枠は各200要求・3時間、OpenAI USD20を承認済み。
ローカルの先行試験は公式DeepSeek APIの受入とは区別する。本番 gate は閉じたまま。Phase 8〜10 は未完了であり、
準備コードや PR の状態を実受入・長時間 Run・本番導入の成功に読み替えない。

## Phase 0: 接続先管理・API抽象化

### 基準と前提

- 元worktreeのmain: `18894acb`。未追跡の2設計書とreview/は保持した。
- remote main: `cc12001e845ecc29c1267afe881dc858e6cca6a2`。PR準備時に再取得し、変更なしを確認。
- 動的ツール選択はremote未反映だったため、専用worktreeで通常マージ `8a775e32`。
  既存変更を作り直さず履歴を保持し、最新セキュリティ修正と統合した。
- PR上のarchitecture基準差分はローカル前提コミットのもの。Responsesによる予算緩和はない。
- 実装の検証済みcode commit: `f9b56cfe4415885f20e6e2858f4d02d8dd796a84`。
  最終ドキュメントcommitと最新PR headはGitHubで照合する。

### 実施済み

- API形式の明示、必要最小限の能力、再送方式、不変の実効接続構成。
- APIキーを含まないfingerprint、キー更新と互換性世代の分離。
- featureのモデル/Optionsの構成コピー固定、native Chat optionsの薄いAdapterへの集約。
- root/custom/private/fallbackで未検証Responsesの利用拒否。
- 診断表示から秘密URLを除外。音声補正の未指定上限は従来どおり保持。
- 明示max-toolsと動的公開枠を分離。独立接続先へのroot能力の暗黙継承を禁止。
  fallback候補の最小上限、基本ツール保持、重複拒否、公開枠1〜128を維持。
- 既存/modelの要求モデル引数を維持。動的接続先選択UIは追加していない。
- 設計をPhase 0〜6へ更新し、保存/再送の責務、障害境界、GC/容量、圧縮、セキュリティ、
  提供元差と有効化条件を明文化。
- Spring AI 2.1.0 / OpenAI SDK 4.101.0のPOM、source JAR、実bytecode、公式仕様を確認。
  Springの5ソースは前回展開された調査対象と改行正規化後に完全一致。

### TDDと不具合検出

| 境界 | Red | Green / 改善 |
| --- | --- | --- |
| 明示API選択 | Responses設定が無視され、期待した拒否がない | 本番経路で利用不可を明示 |
| モデルとOptions | cached first-modelとsecond-modelが混在 | 設定コピーを共通化。対象70件成功 |
| root生成経路 | 別composition経路がResponsesを拒否しない | native options生成を共通境界へ委譲 |
| private/fallback/診断 | 明示API無視、異API fallback、URL秘密情報露出 | 期待したAssertionError3件を解消 |
| 接続先能力と公開枠 | 明示能力が要求へ反映されない | 未指定時の既存制限を維持し、明示能力のみ適用 |
| 基本ツール保持 | 接続先上限31でRunを開始してしまう | 基本31件＋検索を保持できなければ開始前に拒否 |
| 既存音声補正 | 未指定上限のint変換でNPE | 任意上限を正確に表現。HTTP内容を変えず6件成功 |
| null安全性 | SpotBugsが3件検出 | 任意上限の型修正とcontextの一度取得で0件 |

Redの初期セットアップにあったコンパイル/classpath/Binder設定の失敗は、仕様上のRedとして数えていない。
短い開発サイクルの独立JUnit出力は全回帰の実行中classesを上書きせず、最終関連回帰でMavenから再実行した。

### 検証結果

| 検証 | 件数 / 結果 |
| --- | --- |
| 統合基盤の初期対象回帰 | 68件、failure/error/skip 0 |
| 初回全回帰 | 7,711件、成功7,697、failure0、error2、skip12。fake接続先の上限不整合を検出 |
| 修正後の全Backend回帰snapshot `279331b3` | 7,717件、成功7,705、failure/error0、skip12 |
| 最新ソースの関連回帰 `-Pfull,quality` | 168件、failure/error/skip 0。音声補正、model/client、能力、出力予算、ComputerUse/SubAgent、architectureを含む |
| Checkstyle | 指摘0件 |
| SpotBugs | 指摘0件、解析エラー0件 |
| Spotless | 成功。統合基盤を含む既存違反36ファイルは独立コミットで整形 |
| architecture | 既存guard/source report/package dependency gate成功。予算・除外を変更せず検証 |
| 最新sourceのpackage/repackage | 成功。専用worktree内のみ |
| 最新PR headの全件CI | 7,718件、成功7,706、failure/error0、期待skip12。Windows A/B・POSIX・集計ゲート成功 |
| 実API / 性能 / production migration | 未実施。このPhaseでは利用不可 |

全回帰後の最終変更は、既存音声補正の未指定上限の表現とnullable contextの一度取得。
最新ソースは再コンパイルして上記168件・全品質ゲートで検証済み。
最新PR headとマージ後の全回帰CIは成功済み。
スキップは既存の[期待IDと理由](../config/test-expected-skips.json)でCI照合し、成功件数へ含めない。

全回帰で見つかったComputerUse179件/SubAgent178件のfake全ツール接続先には256件の能力を明示した。
未指定の184件を拒否する既存テスト、動的公開128件、重複拒否、権限・LRU・元要求snapshotは維持する。
無制限化、検査除外、静的解析の抑制、architecture予算の緩和は採用していない。

### 受入と残作業

ローカルで既存Chat・設定・動的選択/承認/再開に関連する回帰と品質ゲートが成功。
ただしPR差分レビュー、最新headの必須CIと承認、通常マージ、マージ後確認、worktree削除が未完了。
GitHub上の結果と作業記録で確定し、次PhaseでPR番号・merge commitを追記する。

Responsesは本番利用不可。通信・HTTP/SSE・完全保存・承認/Checkpoint・長時間Runの統合は後続Phaseで実装する。
設定descriptorだけを、Run受付時に選んだ要求モデルの代わりにしない。
外部設定、本番JAR、本番プロセス、本番DB、元worktreeの未追跡ファイルは変更していない。
実APIの認証・送信データ・費用・実行権限の確認が不足する場合、未検証の有効化条件として残す。

### 次の作業

Phase 0のPR・最新head CI・差分レビュー・通常マージ・後片付けを確定する。
前Phaseのマージ前にPhase 1を開始しない。再開時は実Git/PR/CIを照合し、記録だけで判定しない。

## Phase 0 のマージ後確認

PR #628はhead6b1d7251を通常mergeし、main d0e3626fと検証済みheadのtree一致を確認した。
PR CI: Java45294837889、Client45294837830、static45294837822はすべて成功。
main CI: Java45296798561、Client45296798127、static45296798041もすべて成功。
必須レビュー・active rulesがないことを照合し、管理者bypassは使用していない。
専用Git worktree登録・元ディレクトリ・local/remote作業branchの削除を確認した。
Windowsで通常削除後に残った自作のignored build artifactsだけは、検証済みのworkspace内targetへ保存した。
元worktreeの未追跡設計書・review/を保持するため、元mainは無理に更新せず次phaseをorigin/mainから作成した。

## Phase 1: 基本通信（マージ・後片付け完了）

基準main: d0e3626f。Spring AI2.1.0/OpenAI SDK4.101.0を引き続き使用する。
TDDで実JSON・native SSE・loopback HTTPのfixtureを追加し、最終対象104件が成功、skip/failure/error0。
PRは[#629](https://github.com/mikoto2000/rei/pull/629)。最終headの全件CI確認後に通常mergeした。

| 境界 | Redで検出した問題 | Greenと契約 |
| --- | --- | --- |
| tool-free | tool_choiceが残る | 両toolフィールドを省略。元optionsは不変 |
| raw body | protocolと能力の上書き | protected fieldsを送信前拒否 |
| status | 欠落・incomplete・queued等が正常終了 | response metadataの明示completedだけ成功 |
| SSE | EOF/incompleteが正常終了 | 終端必須。キャンセルではbodyをclose |
| capability | 宣言なしのsampling/reasoning/parallel送信 | 明示設定を能力に照合 |
| request identity | native runtimeのモデル・接続指定が変更/無視される | bound設定との差を送信前拒否 |
| tools | 重複名・不正名・上限超え | 実callback定義を送信前検証。strict:falseを維持 |
| privacy | Spring内部のログにprovider error本文が漏れる | typed SDK stream境界で先に安全化。ログ捕捉テスト成功 |
| retry | native runtimeでretry変更可能 | client構築時と要求時に0を要求。異常HTTPは1回のみ |
| timeout | Responsesだけ既存inactivity制御対象外 | 共通interceptorを拡張。実同期/非同期socket timeoutを分類 |

Structured Outputのtext.format、画像data URL、max_output_tokens/store:false、portable/native usageも
実SDKを通して検証した。独自HTTP client/SSE parserや別agent loopは追加していない。
Responses本番agent実行、実API、再送、永続化・再開、長時間性能はこのphaseで利用可能とはしない。
外部YAML、実運用JAR、本番プロセス・DB、元worktreeの利用者変更は変更していない。

新worktreeでmvnw.cmdが既存の.gitattributesと異なるCRLF indexを持ち、restore後にも差分が残ることを
確認した。working bytesを維持してindexだけを既存規則どおりに正規化した変更を独立commit1433745dに分離した。

Phase1のレビュー追加分も短いRed/Green履歴で修正した。resolved descriptorとclient既定値の一致、
別endpoint向けoptions生成拒否、deployment/timeoutによる隠れた変更の拒否、名前付きtool choiceの
公開範囲照合、Schema重複キーの拒否、verbosity能力、unknown raw拡張の拒否を含む。
incomplete(max_output_tokens)とfailedでは、数値usageだけを安全な例外へ保持する。
SDK response本文やcauseを保持せず、失敗時にも提供済みのinput/output/total/cached/reasoningを失わない。
実接続の未測定値やusage欠落を推測値で埋めない。

ローカル全回帰はsource snapshot45fcd42dで開始し、レビュー追加分は独立出力先のJUnitで継続した。
進行中のMaven classesを上書きしていない。全回帰の結果と最終sourceの対象/品質ゲートの結果を分けて記録し、
最終PR headの全件CIが成功するまでマージしない。

### Phase 1 の検証結果

| 検証 | 結果 |
| --- | --- |
| 全回帰snapshot45fcd42d | 7,791件、成功7,779、failure/error0、既存期待skip12 |
| 初回品質ゲート | Spotless/Checkstyle成功、SpotBugs6件を検出。未成功として記録し修正 |
| 静的指摘修正後の関連回帰 | 372件、failure/error/skip0、全品質ゲート成功 |
| HTTP層再送修正後の最終関連回帰 | 376件、failure/error/skip0。LLM全域・discovery・capture・architecture・AiConfiguration |
| 最終対象の独立検証 | 104件、failure/error/skip0 |
| 最終Checkstyle / SpotBugs | 指摘0 / 指摘0・解析error0 |
| 最終Spotless / architecture / package | すべて成功。既存ゲート・予算・除外の変更なし |
| 最終headのPR CI | head aa0518ef。A3,811+B4,007=7,818、成功7,806、failure/error0、期待skip12。ID/重複/欠落照合成功 |
| 実API・本番操作・長時間性能 | 未実施。production Responsesは引き続き拒否 |

SpotBugsのnullable getter再取得3件と広いRuntimeException投げ直し3件は、値の一度取得と
具体的な安全例外型で解消した。全回帰snapshotの報告を最終sourceの全回帰成功に読み替えていない。
実loopbackではSDK retry:0でも408/503が2回届くRedを4ケースで確認し、本文をnon-replayableと宣言して
同期/非同期とも1回へ修正した。受信JSONは元のSDK JSONと一致する。共有5秒timeoutよりresolved150msが優先する。
通常ログへprovider本文や推論を出さないログ捕捉テストも維持する。

### Phase 1 のマージ後確認

通常merge10542db8と検証済みhead aa0518efのtree一致を確認した。
マージ後のstatic45312908900、Client45312908970、Java45312908991はすべて成功した。
専用worktreeは通常のgit worktree removeで削除し、local/remote作業branchの削除も確認済み。
元worktreeの利用者の未追跡設計書・review/は保持した。

## Phase 2: 暗号化推論再送・既存ツール実行（検証中）

基準main10542db8。native SDKを通すHTTP JSON/SSE fixtureと既存実行ループを使用する。
本番経路では引き続きResponsesを拒否する。DB移行・実API・本番設定変更は実施していない。

| Redで検出した問題 | Green / 結合試験 |
| --- | --- |
| 同じtoolCalls setterでitem属性・パーツ順序が失われる | 不要な再構築を避け、複数reasoning/text/toolの順序を保持 |
| ファイル継続引数の補完時にitem属性が落ちる | 変更するToolCallPartだけ置換し、payloadと属性・位置を保持 |
| 引数補正経路でreasoningが履歴から消える | 同期中の実行viewと元assistantを区別。元partsを次要求へ保持 |
| 必須payload/item id欠損、provider/kind不一致をnative mapperが省略する | HTTP前に明示失敗。payload bytesの完全一致を確認 |
| 別owner/endpoint/model/世代への再送が可能 | 明示backend ownerとfingerprintを照合。キーだけの更新は許容 |
| completedでも再送必須情報が欠けた応答からツールが動く | 完全parts検証を正常完了の前に実施。同期・streamとも実行0件 |
| 重複id・未完了function・壊れたJSON・hosted itemを受理する | terminal応答の整合性検証で拒否 |
| partial/unknown saved partを省略・再送できる | input境界で拒否。欠損をnoneで回避しない |
| max_output_tokensが既存出力上限回復へ届かない | native LENGTHとusageを既存処理へ接続。truncated batchは共有境界でも拒否 |

動的公開は既存StagnationChatModelで32→33件を確認し、searchToolsの効果が次要求から適用される。
未公開siblingを含む応答は検索自体より前に拒否し、次要求は32件を維持する。
DENY/REQUIRE_APPROVALは既存guardを通り、業務callbackは実行されない。
BoundedToolLoopでもEOF、failed、incomplete、cancelled、output limit、buffer中のキャンセルで実行0件。
独自agent loop、独自HTTP client/SSE parser、interceptorのJSON書き換えは追加していない。

短いTDDはRed/Greenの独立commitで記録した。関連最終159件、failure/error/skip0。
全回帰・静的解析・architecture・PR/CIの結果は確認後に追記する。
承認待ちの完全なparts保存・プロセス再起動後の復元・実Runの所有者接続はPhase 3・4で実装し、
このphaseのin-memory fixtureの結果を永続化済み・本番利用可能とは扱わない。

PR差分レビューで、Spring MessageAggregatorがnative cached/reasoning内訳を落とすことを追加Redで検出した。
標準集約器の再実装はせず、raw terminalのnative usageと、集約をまたぐbackend metadataの正規化5数値を
それぞれ検証する。正常完了・出力上限・整合性エラーで内訳を保持し、未提供値を推測しない。
全回帰snapshot ab6e3b45ではarchitecture依存1件を検出した。予算を緩和せず共有境界内の判定へ修正し、
production snapshotの独立architecture検証は成功した。snapshot全回帰の結果と修正後の結果は区別する。

### Phase 2 の完了確認

PR #630をhead a276eaf3の全チェック成功後、通常merge a95593f4で統合した。
PR CI: static45321007826、Client45321007866、Java45321007834 attempt2がすべて成功。
JavaはA3,811+B4,050=7,861、成功7,849、failure/error0、既存期待skip12。
集約138214725669でテストID・クラス割当・重複・欠落・skipを照合した。
ローカル最新sourceの関連859件とSpotless/Checkstyle0/SpotBugs0・解析error0/architecture/packageも成功。
独立した同一条件のB4,050件も成功し、skip10・failure/error0・12分22秒だった。

初回Bは既存の実行制限を超えて応答せず、ログも未公開だった。通常キャンセルにも応答しなかったため、
GitHub公式の復旧手順でこのCI実行だけを停止し、同じheadのBと依存集約を再実行して成功した。
ソース・制限・skip・予算・保護ルールは変更していない。停止原因は未確定として記録した。
修正前snapshot ab6e3b45の全回帰7,860件はarchitecture依存1件が失敗した結果のまま保存し、
修正後の全件成功や最新sourceの結果と混同していない。

マージ後のexact main a95593f4でも、static45325944502、Client45325944554、Java45325944603がすべて成功。
集約138219711283でA3,811+B4,050=7,861、期待skip12、割当・ID照合の成功を確認した。
検証済みheadとmainのtree一致を確認し、専用worktreeは通常削除、local/remote作業branchも削除した。
元worktreeの未追跡設計書・review/と他のworktreeは保持した。

## Phase 3: モデルメッセージの永続化（実装・検証中）

基準 main は Phase 2 merge `a95593f48d0b4f760091d79eaf2cbc411e9a2f49`。
専用 worktree とブランチで継続し、元 worktree の利用者ファイル・外部設定・本番 DB は変更しない。

### 実装済み

- ModelMessageCodec: 明示的な version 1 JSON、ordered parts、opaque bytes/ID、全4role、画像、型付き配信情報、
  backend owner/fingerprint、共通の5数値 usage。strict JSON、許可 part/metadata、UTF-8 8 MiB等の容量上限、
  raw stream chunk の拒否、旧 StoredMessage の限定的な互換読み込み。
- ModelMessagePayloadStore: qualified memory DB 内の専用 version 3 schema。所有者は project/root/conversation、
  origin run は別。内容ハッシュ、実長、所有者、冪等キーを照合し、本文 GC 後もキーの再利用を拒否する。
- 容量判定・本文・receipt・Run 累積量を SQLite の同一トランザクションで保存する。
  初期値はメッセージ8 MiB、Run 生涯64 MiB、現在の本文合計512 MiB、staging 猶予1時間。
  DB/WAL/index/receipt の物理容量は本文量と分け、Phase 6 の実測対象とする。
- HISTORY/RUN/APPROVAL/CHECKPOINT の参照と保存途中の猶予を区別する。期限切れ孤立 payload の遅い公開は拒否。
  公開済みのどの参照も GC から保護する。独立 Scheduler は作らず、既存の maintenance CLI へ GC を追加。
- 既存 ToolLoopSupport の明示的 Exchange により、元 assistant の保存・RUN 参照公開を callback より先に行い、
  配信 metadata 付き結果を保存してから次の履歴を返す。保存失敗は例外として上へ返し、別の実行ループ・再試行を追加しない。
- ConversationLog/Turn は本文を複製せず3文字列の pointer を持つ。ログの metadata 付き append は同期保存の失敗を返す。
  Turn の既存 recordMetadata を再利用し、履歴は公開済み pointer を優先する。テキストのみのログに対応する Turn の
  pointer も優先し、二重追加しない。参照が失われた場合に本文 fallback で継続しない。旧完了会話は従来どおり読める。
- history sequence 付与時に reasoning/tool attributes や ToolResponse の型が失われる処理を修正。
- native usage を共通 immutable record へ昇格。不正な typed JSON、負数、小数、overflow、合計・内訳不整合を
  Spring の mapper より前に拒否する。数値が未提供なら補完しない。

### ここまでの検証

| 対象 | 結果 |
| --- | --- |
| native Responses / Codec の usage 検証 | 最新207件成功、failure/error/skip0。JSON/SSE・実SQLite・usage未取得/ゼロの区別を含む |
| クリーンコンパイル後の Responses / SQLite / architecture snapshot | 217件成功、failure/error/skip0。後続変更前の snapshot |
| 最新の Codec / SQLite / ToolLoop / history / log / maintenance 開発選別 | 103件成功、failure/error/skip0 |
| 別プロセスによる保存・復元 | Writer JVM の終了後に Reader JVM で全 parts・opaque・call ID を復元 |
| 並行・障害注入 | 8 writer と GC、上限の競合、DB途中保存失敗、DDL途中移行失敗、将来 Schema、本文改ざんを検証 |
| 実保存先の再生成 | payload SQLite・Turn SQLite・JSONL を保存し、全 Store を作り直して参照優先を検証 |
| Maven 最新関連回帰 | 570件成功、failure/error/skip0 |
| Checkstyle / SpotBugs | 指摘0件、解析エラー0件 |
| Spotless / architecture / package・repackage | 全成功。予算・除外の変更なし |
| 実 API / 本番移行 / 運用プロセス操作 | 未実施 |

Red の fixture 準備での不備（Windows の長い子 JVM classpath、isolated javac の -parameters不足、
maintenance test の storage.db未準備）は、期待した仕様上の Red と区別し、修正後の Red/Green を記録した。
初回 clean はログを target 内に置いたため Windows のロックで停止した。ログと TDD evidence を外へ保存してから
クリーンビルドを再実行し成功した。検証ゲートの緩和、強制削除、本番プロセスへの操作は行っていない。

初回の関連 Maven 検証は554件、成功553、failure1、error/skip0。失敗は architecture の新しい
Configuration 依存3本と新コマンドから既存循環領域への依存1本で、機能テストは成功した。
baseline や予算を増やさず、lazy component と ObjectProvider の注入、既存コマンドの実行境界へ変更して
同じ検証で architecture 成功を確認。続く SpotBugs の6件（nullable/constructor/注入可視性）と反射注入の指摘2件も修正し、561件と全品質ゲートが成功した。
その後、native SSE 集約が未提供 usage をゼロに変える再現テストを追加。明示的な null の5数値を backend metadata へ保持し、未取得とゼロを区別した最終565件・全品質ゲートが成功した。
接続開始の RuntimeException が内部設定・causeを露出するケースも Red/Green で修正した。初回の失敗を成功と読み替えない。

### 有効化境界と次の作業

Phase 3 の fixture は明示的な per-iteration Exchange と参照契約を検証する。
全クライアントへの Run/iteration identity の供給、承認・Checkpoint の full-parts 参照、操作台帳との再開照合、
圧縮・終了・削除 lifecycle の参照解放は Phase 4 の有効化条件として残す。本番 Responses の生成は引き続き拒否する。

Phase 3 は PR #631 の差分レビュー・修正後の全 CI・通常 merge・merge 後 CI が成功し、専用 worktree とブランチを削除した。
PR head とマージコミットの tree 一致も確認。最新 main `b22cf664` を基準に Phase 4 を開始した。


### Phase 3 の PR 差分レビュー

PR #631 の全20ファイルを確認し、assistant call ID の重複・空白が保存時に通る非対称性を検出した。
ToolResponse と旧形式に加え、現行 assistant の encode/decode でも拒否する3件の Red を確認し、
da74dfb6 → 7600c0cc で修正した。native/Codec 207件、修正後の関連568件と全品質ゲートが成功した。
API固有処理・DB・ツール実行の分離、所有者/ハッシュ/参照の照合、旧完了会話、容量・GC、
未検証Responsesの本番拒否を照合した。最終全件CI・通常merge・merge後確認・cleanupも完了した。


追加レビューで、同時刻の複数Turnからprotocol参照を選ぶ曖昧性と、同じ表示文の通知への誤付与を2件で再現した。
4e4057ad → 4f6a18c6 で、参照を含む時刻の対応が複数なら停止し、通知を補完対象から除外した。
関連108件、最新Maven関連570件と全品質ゲートが成功した。既存の参照を持たない旧履歴の順序は維持する。

最終 PR CI は Java `45345004343`、静的解析 `45345004446`、クライアント `45345004146` が全成功。
マージ後も Java `45346995855`、静的解析 `45346995780`、クライアント `45346995953` が全成功。
Windows A 3,816件・B 4,144件、期待skip12、failure/error0。集約ゲートで分割・baseline・ID・期待skipを照合した。
旧 head の停止不能な CI は通常 cancel 後も終了しなかったため公式 force-cancel を使用した。原因は未特定。
最新 PR head とマージ後の実行は再試行なしで成功し、検証ゲートや制限時間は変更していない。

## Phase 4: 長時間 Run・既存基盤への統合（全回帰検証中）

### 実行と再開の境界

Responses は既存の Run、ToolLoop、Stagnation、承認、Checkpoint、Context Compression を使う。
API ごとの実行ループや別の操作台帳は追加していない。
受け付けた native options と接続先を Run の入口で一度固定し、通常・読み取り専用・ツールなしの3モードへ渡す。
子 Run は子自身の scope・接続先・再送権限を持ち、親の opaque payload や承認権限を継承しない。
動的ツール公開の元要求 snapshot、公開上限、権限検査は引き続き既存基盤が所有する。

HTTP 開始前に、元の UserMessage（画像を含む）と、advisors・圧縮を適用した実際の送信入力を参照保存する。
保存対象は System/User/Assistant/Tool と、配信属性・履歴 sequence・元の protocol parts である。
入力保存が失敗した場合は HTTP とツールを開始しない。応答保存が失敗した場合もツールを開始しない。
保存前の失敗を、モデルの使用量不明として計上しない。

承認は元の assistant 参照、成功済み結果 prefix、入力全体、予算を保存する。
別 JVM で復元しても成功済み sibling を再実行せず、残る承認対象だけを既存の操作台帳・権限検査へ渡す。
待機中の追加指示も保存・参照保護が成功してから受理通知する。
入力容量は文字数ではなく UTF-8 バイト数で検査する。
旧 schema の未完了承認を native な元応答へ捏造して移行しない。

Checkpoint は元入力と自 Run 履歴の完全な参照集合を保護する。
既存の resume routing を通る別 JVM 試験では、ツール結果の保存後に子 JVM を終了し、
新しい Run で元 input・reasoning・call_id・結果を復元して最終回答まで到達する。
成功済み操作は1回だけで、再開時の追加実行はない。
接続先世代・ツール定義・基本システム指示を変更した3条件では、再開先 HTTP より前に拒否する。
未完了の callback を含む Checkpoint は安全側で停止する。UNKNOWN な外部操作を自動再実行する仕組みは追加していない。

再開の互換性はモデル、出力上限、推論設定、形式、公開ツール定義、基本システム指示の指紋で検査する。
API キーや authorization header は実行ポリシーの指紋へ直接保存しない。
時刻・作業状態など host 側の観測更新を、基本システム指示の変更と混同しない。
Checkpoint の参照移管は、公開済みの旧 revision と同じ参照集合を検証して行う。
RUN 参照を復活させて移管の証拠を捏造しない。

### 完了履歴・圧縮・保持

完了した native 会話は、元の protocol message への HISTORY 参照と、件数・範囲を持つ manifest で公開する。
未完了・失敗・キャンセルの履歴は次の通常会話へ公開しない。
別接続先・別 API の会話は、既存の表示テキストによる互換履歴とする。
同じ接続先の履歴にも、scope と公開済み参照を検証した backend の再送権限が必要である。

圧縮は完了した User/Assistant/全ツール結果の組を単位とし、現在の User や未完了 transaction を残す。
全ページの要約・検証・cursor の保存が成功してから進める。失敗やキャンセルで cursor を進めない。
暗号化データを解読して推定トークンへ変換しない。
完了結果・履歴・継続記録の確定後に、その Run の RUN 参照だけを解放する。
後片付けの DB 障害で、保存済みの成功結果を失敗へ変えない。
FAILED/CANCELLED/UNKNOWN/承認待ちの参照は保守的に保持する。
承認終端・要約済み履歴の参照整理、長期保持時の容量・GC 競合は Phase 6 の運用検証に含める。

### 機能別クライアント

API 固有の options 変換は ModelOptions に集約する。
単発の補助要求には独立した feature owner を発行し、親の再送 grant・origin・exchange を持ち込まない。
既存の Run 予算・キャンセル・使用量計上は維持する。

| 経路 | native 向けの確認内容 |
| --- | --- |
| Agent Skills 選択 | メタデータによる選択、既存予算、独立 owner、選択時に指示本文を公開しない |
| Work Context / Sleep / 文脈圧縮 | native options、機能側の出力上限、独立 owner、既存の使用量・期限管理 |
| Planning / 出力上限回復 | 実 SDK の4要求で、主要求41・planner73・global2048を区別し、主要求の41を計画へ渡す |
| 論文 / briefing / Bluesky 返信 | 独立した単発要求。返信履歴は表示テキストだけを記憶し、別要求の opaque parts を再送しない |
| Activity 分類・時系列推定・日次要約・画面解析 | JSON Schema strict、native 出力上限、元画像、ツールなし。既存の CC completion 上限指定を維持 |
| 作業習慣メッセージ / 関心抽出 | 評価済みデータ・入力上限・期限を維持し、native options と独立 owner を使う |
| 音声補正 | native 出力上限、機能側5秒と接続期限の短い方、使用量不明時の予算拒否。独立した音声 API は変更なし |
| 診断 / 教材レビュー | 既存 BoundedToolLoop と Run 予算、ツールなし、受け付けた設定の固定 |
| Computer Use planner / grounding / verifier | 合成画像で native options と既存の拒否・検証を確認。ログ・診断には表示テキストだけを出す |

Chat Completions 専用の local thinking template を Responses へ暗黙変換しない。
UI-TARS の frequency penalty は Responses では明示的に拒否する。専用 CC 接続設定は維持する。
SearchCommand の旧来のツール付き ChatClient 経路は CC 専用のままであり、Responses 対応済みとは表示しない。
明示的に公開済みのツールを消して単発要求へ変える回避策は採用していない。

### 検証と本番利用可否

開発中の失敗テスト・修正は Red / Green の個別コミットに記録した。
セットアップ・コンパイル・fixture の誤りは製品仕様の Red として数えない。
関連 Maven snapshot 913件、architecture、Spotless、Checkstyle違反0、SpotBugs指摘/解析エラー0、package が成功した。
その後の別 JVM Checkpoint 4条件、Activity 43件、分類15件、作業習慣4件、関心14件、
音声4件、画面操作38件、診断・教材レビュー21件は隔離した開発テストで成功した。
これらの件数は対象の重複を含むため合算しない。
初回の全回帰 snapshot `d79cd6a66` は8,167件、成功8,128、failure16、error11、想定skip12で失敗した。
失敗は関心抽出の新しい Core 依存1スイートと、要約の旧 mock 3スイートに限られた。
生ツール検査を options 共通境界へ移し、mock を新しい requestOptions 入口へ合わせて修正した。
予算・キャンセル・usage・元入力・HTTP の検証条件は緩めていない。

追加検証で見つかった native 要約上限の引き上げも修正した。
生成 reserve を加えた要求でも、受け付けた接続上限を超えない。
CC の出力トークンのパラメーター種類と従来の指定は維持する。
JSON Mode と共有 ResponseFormat の Schema は、実 SDK が `text.format` へ変換することを確認した。
Refusal は Schema に一致する正常出力として扱わず、最初の SSE delta でも通常の受信側より前に停止する。
終端で受信済みの数値 usage は保持し、終端前に止まった usage を推測して埋めない。
既存の安全な外部読み取りエラー契約を使い、認証・一時障害・タイムアウト・Refusal など9分類を利用者向け診断へ渡す。
拒否本文・SDK 本文・原因例外は診断に含めない。
この区別は [OpenAI の Structured Outputs 仕様](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses) と、固定した Spring AI の公開 metadata の実装を根拠とする。

これらは実ソースで失敗を再確認してから修正コミットへ進めた。
修正後の関連445件がすべて成功した。
修正後の127スイート・1,065件と全アーキテクチャ検査、jar/repackage、Spotless check が Maven で成功した。
その実行は追加テストのワイルドカード import 12件で Checkstyle が失敗した。
import だけを明示化し、診断の builder の型推論について SpotBugs が検出した1件も明示的な変数へ修正した。
診断・教材レビュー21件を再確認し、全ソースの再コンパイル、Checkstyle違反0、SpotBugs指摘0・解析エラー0、Spotless check が成功した。
検査の除外・抑制・基準値変更は行っていない。
最新 head の全回帰は PR の CI で確認し、その成功前にマージしない。
以前の全回帰8,113件で見つかった Activity の不要な DI 呼び出しも修正済みで、今回の全回帰では成功している。
古い失敗 run を成功扱いしない。

実 SDK の loopback fixture と永続化 DB による契約検証であり、実 OpenAI の暗号検証や推論品質を証明しない。
実接続での reasoning の受理・文脈変更後の扱い・Prompt Cache・TTFT は未検証である。
本番 Responses の生成ゲートは閉じたままにし、テスト専用の factory だけを利用する。
実接続の認証情報・許可がないため、本番利用は不可と判定する。
有効化には実接続の受入試験、未対応経路の明示、Phase 6 の容量・保持・異常終了検証が必要である。
本番再起動、JAR 配置、本番 DB migration、課金 API 呼び出しは実施していない。

## Phase 5: DeepSeek plaintext replay

### 仕様の根拠と境界

[DeepSeek 公式 Responses 仕様](https://api-docs.deepseek.com/guides/responses_api/) と、
固定した Spring AI 2.1.0 / OpenAI SDK 4.101.0 の公開 API・ソースを照合した。
DeepSeek の reasoning input は平文 content を受け付け、encrypted_content を受け付けない。
reasoning_text.delta / done と終端 response の両方を fixture に含めて検証する。
同名モデルを提供する他社 API に、この仕様を自動適用しない。

Spring の mapper は受信した平文を ReasoningPart に保持するが、そのままでは再送しない。
既知の ReasoningPart だけを、所有者・接続先・世代・公開参照の既存検証後に、
送信用 view の UnknownPart に変換する。正本・SQLite・承認・Checkpoint は ReasoningPart のまま保持する。
平文を opaque / encrypted_content へ入れない。未知 item をローカル業務ツールへ変換しない。
空 summary は固定 SDK の input 型に必要な構造であり、推論 summary の対応を意味しない。

DeepSeek は summary、verbosity、parallel_tool_calls などを黙示的に無視する。
対応能力にそれらを宣言せず、明示設定は送信前に拒否する構成が必要である。
temperature / top_p にはモデルと thinking mode による制約があり、HTTP 200 を設定の有効性の証明にしない。
本番生成ゲートは閉じたまま。実接続・課金 API・production migration は実施していない。
本番利用可否は未検証のため不可。fixture の成功をベンダーの意味的継続性・品質の実証と扱わない。

### 検証状況

実ソースの Red 2件（非stream / stream の平文拒否）から開始し、送信 view の修正後に2件成功。
複数 reasoning item の順序・全文、encrypted_content の不在、原本 parts の不変性、実 SQLite 復元を確認した。
provider の拒否も Red 1件で再現してから修正し、明示した fixture 接続だけを受け付けた。
既存 encrypted / none を含む再送契約117件が成功。
追加の SSE delta・承認・別 JVM Checkpoint 14件も成功した。新旧双方を同じ既存経路で検証している。
SDK の公開 typed request builder で、平文要求の自動 encrypted include を除いた（Red 2件からGreen）。
明示した非対応設定10件、欠損・偽装・所有者・接続先・未完了・未知属性8件、
受信 wire の未知属性4件は固定診断で停止し、本文や原因例外を公開しない。
これらの件数は一部が重複するため合算しない。
関連 Maven の18スイート400件と全 architecture、jar/repackage、Spotless check が成功し、
Checkstyle は違反0件だった。ただし、この verify 実行は SpotBugs の nullable getter 指摘5件で失敗した。
getter を一度取得する形へ直し、平文の送信・非対応設定・安全性24件を再確認した。
最初の strict compile では Kotlin SDK の JsonField<Nothing> に相当する missing singleton の
Java 型消去警告も検出した。値を持たない missing への局所的な型変換だけを明示し、
既存 Werror・静的解析ゲート・アーキテクチャ予算は維持している。
修正後の全ソース・テスト compile、Checkstyle違反0、SpotBugs指摘0・解析エラー0、Spotless check が成功した。
最新 head の全回帰 PR CI 成功前にマージしない。
旧 verify 全体を成功と記録しない。


## Phase 5 の最終照合

PR #633 を通常 merge し、merge SHA は `f9fd5a5ebbd4fff2fe65781373d9af4f31027276`。
PR 最新 head と merge tree は一致し、全回帰 8,215 件（成功 8,203、期待 skip12、failure/error0）、
静的解析・全クライアント CI が PR とマージ後 push の双方で成功した。
最初の Windows A は既存 sqlite-vec の外部ダウンロード timeout 1 件で失敗したが、
ソース・skip・ゲートを変更せず failed jobs のみ一度再実行し、最新 attempt の全 shard と aggregate を確認した。
通常 worktree 削除・ローカル作業ブランチ削除も完了した。次 Phase はこの merge から開始している。

## Phase 6: 運用・長時間実行

開始点は上記 Phase 5 merge、専用 worktree は `.worktrees/responses-api-phase-6`。
本番プロセス・JAR・外部設定・実運用 DB を変更せず、SDK HTTP fixture と所有済み SQLite で検証する。

| 実際の Red | Green と判断 |
| --- | --- |
| SDK delta 5 件・nested item の 6 件で PRIVATE canary が内側 logger に現れる | 公開 typed getter / validate を handler に渡す前に検証し固定例外へ正規化。独自 SSE parser は追加しない |
| COMPLETED / DENIED の承認 pin 4 条件が残る | durable state 後、外側 transaction は afterCommit で該当 APPROVAL だけ解放 |
| cleanup 文書破損・lookup I/O で確定済み terminal state の呼び出しが例外になる | cleanup 全体の固定診断と安全側の保持。rollback と他 anchor は維持 |
| normalized native の retry 4 条件・Main timeout 2 条件が既存許可に到達しない | 安全な retry advice だけ既存ループへ渡す。X-Should-Retry:false・不明 token・回数上限を維持 |
| 完了済み要約の encrypted / plaintext 履歴 pin が残る 2 件 | fully covered manifest / scope / receipt / hash を本文なしで照合し原子的解放 |
| 120 ラウンドで CONTEXT_HARD_LIMIT、途中修正では圧縮回数 1 の assertion failure | host guidance の蓄積を防ぎ、動的公開時も raw history と actual options を維持。23 回圧縮と直前 output の全要求保持を確認 |

fixture の classpath・schema・必須保存先の配線ミス、コンパイルエラーは仕様上の Red として数えない。
Goal は既存 GoalLoopService / GoalRepository / ChatExecutionService / 成果物 SHA 検証を通し、
正常完了と token 超過時の callback 0 件を encrypted / plaintext の双方で確認した。
SubAgent は親の replay 権限を引き継がず、平文4条件を追加した。
SQLiteFull は所有済み DB のページ上限で注入し、本文・receipt・容量計上の rollback と既存 CP 保持を確認する。

ローカル関連回帰は 55 スイート **818 件、failure/error/skip0**。
この verify は書式チェックで失敗したため、全体成功とは記録しない。
変更14ファイルに Spotless を適用し、全 architecture を含む再検証227件も failure/error/skip0。
最終の全main/test compile、jar/repackage、Spotless、Checkstyle違反0、SpotBugs指摘0・解析エラー0が成功した。
この最終品質コマンドはテスト再実行を省略しており、818件と227件の実行結果を置き換えるものではない。
最新 native 再送契約169件は、別JVM Checkpoint、120ラウンド、承認 payload の期限切れ・GC・Store再起動を含み成功した。
実測・14機能の対応表は [検証記録](responses-api-validation.md)、設定・移行・切り戻しは [運用手順](responses-api-operations.md)。

未検証の実 API での暗号受理、意味的継続性、品質、cache、TTFT は受入条件に残す。
本番 factory は閉じたまま。全回帰・全品質・PR・通常 merge・マージ後 CI が完了するまで Phase 6 を完了扱いしない。

Phase 6 の上記状態は PR 作成時点の snapshot。最終の merge SHA・最新 head CI・マージ後 push CI・cleanup は、
[PR #634](https://github.com/mikoto2000/rei/pull/634) の最終実績と Git の状態で照合する。
