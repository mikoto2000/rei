# Responses API 実装進捗

更新日: 2026-11-24。完了判定はGit・PR・CI・実装・検証結果と照合する。

| Phase | 状態 | ブランチ / worktree | PR | マージコミット | 検証 |
| --- | --- | --- | --- | --- | --- |
| 0 | MERGED | codex/responses-api-phase-0-foundation / 削除済み | [#628](https://github.com/mikoto2000/rei/pull/628) | d0e3626f9565035cab20cc045dd3ba070985d3fe | PR・マージ後CI成功。全件7,718、成功7,706、skip12、failure/error0 |
| 1 | MERGED | codex/responses-api-phase-1-transport / 削除済み | [#629](https://github.com/mikoto2000/rei/pull/629) | 10542db848d9317c7eaaab06869e63e7ab571d5b | 最終CI全件7,818、成功7,806、期待skip12、failure/error0。品質ゲート成功 |
| 2 | IN_PROGRESS | codex/responses-api-phase-2-openai-replay / .worktrees/responses-api-phase-2 | 未作成 | 未マージ | 関連158件成功。全回帰・品質ゲート検証中 |
| 3 | NOT_STARTED | — | — | — | 未実施 |
| 4 | NOT_STARTED | — | — | — | 未実施 |
| 5 | NOT_STARTED | — | — | — | 未実施 |
| 6 | NOT_STARTED | — | — | — | 未実施 |

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

短いTDDはRed/Greenの独立commitで記録した。関連最終158件、failure/error/skip0。
全回帰・静的解析・architecture・PR/CIの結果は確認後に追記する。
承認待ちの完全なparts保存・プロセス再起動後の復元・実Runの所有者接続はPhase 3・4で実装し、
このphaseのin-memory fixtureの結果を永続化済み・本番利用可能とは扱わない。
