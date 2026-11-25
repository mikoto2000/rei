# Responses API: 本番利用準備と段階的導入

> この文書はPhase 10の受入結果と当時の制限を記録する。現在のモデル選択と有効化条件は
> [運用手順](responses-api-operations.md) を参照。現行ソースは旧 `accepted-responses-profile` を参照せず、
> YAMLの `model` を使う。以下の実API受入結果は当時のモデルと条件に限る。

更新: 2026-11-25。Phase10のソース・設定例・移行試験・ローカル全検証は完了。
この文書はソースcommit時点の記録。最新PR head/マージ後exact SHAのCI・merge commit・cleanupは当該PRとworktree外の最終報告で照合する。
[Phase10 PR #638](https://github.com/mikoto2000/rei/pull/638) に最終のGit/CI/後片付けを記録する。
**本番プロセス・JAR・クライアント・外部設定・本番DBは未変更。本番適用は未承認。**

## 接続先ごとの判定

| 接続先 / モデル | 実API | 長時間・結合試験 | ソースgate | 本番適用 |
| --- | --- | --- | --- | --- |
| OpenAI / `gpt-5.6-sol` | 宣言した合成試験はPASSED | 50操作/58要求、実圧縮7回。調査4ファイル・動的公開・圧縮もPASSED | 下記の固定profileを明示した2用途だけ許可。既定は閉鎖 | 未承認・未適用 |
| 指定ローカル / `deepseek-v4.1-flash-1017` | 基本通信・再送等はPASSED | 50操作/58要求・実圧縮6回はPASSED。結合調査は2試行FAILED | 閉鎖を維持 | 不可 |
| DeepSeek公式API | UNVERIFIED | UNVERIFIED | 閉鎖を維持 | 不可 |
| その他モデル・接続先・用途・能力 | UNVERIFIED | UNVERIFIED | 閉鎖を維持 | 不可 |

ローカル初回の調査は、専門ツールを追加公開した後に既読ページを再要求して拒否された。
同じ条件の再試行は最終JSON不成立。成功したLongとは独立にFAILEDとして保存し、権限・重複拒否を緩めない。
単発の合成試験成功は、任意業務の品質・非決定的モデルの信頼性・全能力の認定ではない。

## 明示profileと設定

[OpenAI設定overlay](responses-openai-accepted.example.yaml) は実装と照合する対象。
`accepted-responses-profile: openai-gpt-5.6-sol-low-20261125` がない既存設定はnativeを有効化しない。
このprofileはSpring AI **2.1.0** / OpenAI SDK **4.101.0**での2026-11-25受入結果に対応する。
モデル・SDK・再送互換性が変わる場合は実受入をやり直し、新しい判定とprofileをレビューする。

| 実効項目 | 許可する値 |
| --- | --- |
| 用途 | `chat`、`output-limit-planner` |
| URL / model | `https://api.openai.com/v1` / `gpt-5.6-sol` |
| API / generation / key reference | Responses / 1 / 用途固有。キー値は比較・fingerprint・報告へ含めない |
| replay / capability | encrypted / max-tools36、supported-settingsはreasoning-effortだけ |
| output / temperature | 2048 / 未指定 |
| effort / tier | low / default。値が欠落する要求では型付きOptionsへ補完し、別値は送信前拒否 |
| timeout / retries | 実効120秒 / SDK0。短いRun期限は既存機構でさらに制限 |
| fallback | false。CCへの自動切替なし |

全`ResolvedLlmEndpoint`を照合する。再送互換fingerprintだけでは出力上限・timeout等を判定しない。
構成時に固定したsnapshotを既存Runへ適用し、設定の後変更で書き換えない。
現行外部設定の共通timeout10分は維持できる。profileはnativeだけを`min(共通timeout,120秒)`とし、
実効値が受入済み120秒と異なる構成は拒否する。共通timeoutを延ばしてもnativeは延長されない。
キー未設定・空・dummy-keyは拒否する。キー更新だけでは互換fingerprintを変えず、所有者変更は世代変更と再受入が必要。

要求時のmetadata・prompt-cache-key・safety identifier・追加includeは未検証として拒否する。
必須encrypted includeだけを保持し、extraBody・hosted tools・server conversation・自動fallbackを追加しない。
異なるSubAgentモデルは親のprofileを借用できない。同じ受入モデルの子だけを利用できる。
voice correction等の未検証用途はchatのnative受入を暗黙継承しない。必要ならその用途へ従来CCを明示する。

overlayの圧縮設定も一緒にレビューする。summary512+生成余裕1024=1536はnative上限2048内。
現行外部設定の`summary-enable-thinking: false`はCC向けなので、overlayのnullで明示解除する。
lowはnative profile側へ固定される。既存falseを継承してnativeへchat_template_kwargsを送らない。
既定のsummary3000+余裕8192をこのprofileへそのまま使うと上限を超え、既存ガードで停止する。
profileはAPI/モデル/クライアントの実効envelopeを判定し、任意の圧縮閾値・業務タスクまで認定するものではない。
例の圧縮・公開枠・Run予算がPhase9の合成Longと対応する。共有の圧縮値を変えるため、CCへの切戻しでは前設定も復元する。

現行設定から追加するのは、用途ごとの明示native構成、公開枠36、上記の圧縮/予算設定。
外部YAMLの現行値はcontext-limit850000/threshold120000/hard-limit160000/summary-input24000、
call-budget無効/max-calls100である。例では受入済みの小さな圧縮枠と、予算有効/64要求/100万トークンへ変更する。
`spring.ai.openai`の既存ローカルCCとembedding、image-generation/computer-use等の別用途はこのoverlayで変更しない。
キーは`OPENAI_API_KEY`から環境で与える。架空の`rei.llm.features.chat.reasoning-effort`は追加しない。値はprofileでlowに固定する。
[ローカル診断例](responses-local-unverified.example.yaml) は意図どおり本番gateで拒否される。利用可能な本番例とは扱わない。

## 実API・fixture・未検証の対応表

CC列は既存の課金なし回帰であり、今回CCの実接続を追加実施した意味ではない。
OpenAI/ローカル列の実APIは[Phase7](responses-api-phase7-acceptance.md)、[Phase8](responses-api-phase8-acceptance.md)、
[Phase9](responses-api-phase9-acceptance.md)の宣言した構成とシナリオに限定する。

| 機能 | CC | OpenAI Responses | 指定ローカル Responses |
| --- | --- | --- | --- |
| 通常会話 / Streaming | fixture回帰 | 実API PASSED | 実API PASSED |
| Function Calling / 動的選択 | fixture回帰 | 実API PASSED、圧縮結合もPASSED | 単独実API PASSED、圧縮調査結合FAILED |
| 推論再送・SQLite・別JVM | native再送は対象外 | encrypted実API PASSED | plaintext実API PASSED |
| Structured Output / JSON Mode | 既存fixture | 実API PASSEDだが今回の運用profileでは宣言しない | HTTP400、UNSUPPORTED |
| 画像 | 既存fixture | 小画像実API PASSED、運用profileは未解禁 | 小画像実API PASSED、運用profileは未解禁 |
| 承認grant/deny・Checkpoint | fixture回帰 | Phase7実API PASSED | Phase7/8実API PASSED |
| 新profileの結果保存後halt/別JVM再開 | fixture補完 | 実Core component PASSED、成功済み効果1回 | 同条件PASSED |
| Goal / local SubAgent | fixture回帰 | 実API PASSED、durable/親子境界確認 | 実API PASSED、durable/親子境界確認 |
| Planning | fixture回帰 | 実component PASSED、Core全自動回復はfixture補完 | 同条件 |
| Context Compression / 50操作 | fixture回帰 | 実API PASSED | 実API PASSED |
| SSE中キャンセル | fixture回帰 | 実Run CANCELLED、不明usage予約保持 | 同条件 |
| 予算 / timeout / 異常系 / GC | fixture回帰 | 実計上・予約、異常系/GCはfixture補完 | 同条件 |

reasoning summaryの有用性、再送省略B対照の安全条件、全crash点、任意旧JARの新DB互換性はUNVERIFIED。
試行数不足から品質向上・キャッシュ成立率・統計的優位を主張しない。拒否やrate limitを実APIへ過剰負荷で誘発しない。

## 予算・性能・容量

実APIは14:23までに終了。期限14:27:42を過ぎた追加実行は行わない。
初期枠各7要求と追加枠を分け、追加枠各200/入力100万/出力30万/OpenAI USD20は増やしていない。
12:27:42で停止後、利用者承認で時間だけ2時間延長し、元台帳へEを1回追記した。

| 追加枠の最終累積 | OpenAI | 指定ローカル |
| --- | --- | --- |
| 要求 / usage既知 / 不明予約 | 198 / 196 / 2 | 187 / 183 / 4 |
| 入力/出力の予算計上（予約含む） | 317560 / 18276 | 462219 / 49000 |
| usage既知分の入力/出力 | 294824 / 14180 | 421631 / 40808 |
| 詳細既知分の推論 / cached | 2977 / 170248（194要求。別2要求は詳細欠損） | 21996 / 155648（182要求。別1要求は詳細欠損） |
| 費用 | 計測器の保守単価でUSD3.723880、実請求UNVERIFIED | 利用者確認のAPI課金なし |
| Long要求 / 時間 / 操作 | 58 / 177.411秒 / 50 | 58 / 493.165秒 / 50 |
| Long実圧縮 / Run累積payload | 7 / 117545 bytes | 6 / 126987 bytes |
| heap開始→終了（peakではない） | 180503136→274075640 bytes | 182573040→250353704 bytes |

初期枠込みの要求はOpenAI205、ローカル194だが、異なる設定・失敗・対照をまとめた品質率は算出しない。
最初のMediumは双方FAILED、修正後の同条件MediumとLongは双方PASSED。
計画/子タスクは最初の条件不一致と、明示した別タスク/事前予算での成功を区別している。
Longの25書込は順序・内容・単一効果を照合した。不要呼出し全体の総数は推測で埋めない。
store全体のpayload/count/refsは過去の成功・失敗Run保持を含み、単独Runのリーク判定には使わない。
ローカルCPU15.375秒・working set最大712335360・private最大770752512 bytesは途中185.3秒/180標本だけ。
OpenAI CPU/peak、Longの真のTTFT、完全な実請求は未測定。HTTP headers latencyをTTFTへ読み替えない。
DB/WAL/hash、payload保存・復元計測、試行別oracleと不明usageの予約はworktree外の受入証拠に保存した。

## DB移行・復元・未完了Run

このprofile変更はSQL schemaを追加しない。storage.dbは22、専用model-message schemaはmemory-consolidation.db内の3。
合成旧schema19からの既存migrator、再起動時の冪等性、検証済みbackupから空領域への復元を試験する。
CC会話・CC/native未完了Checkpoint・native payload hash/parts・期限切れ候補を守るCheckpoint pinを照合する。
破損backupと新データのある復元先は拒否する。既存の移行障害注入・旧CC schema・protocol transfer拒否を補完証拠とする。
本番DBのコピー・バックアップ・migrationは未実施。合成fixtureの成功を実データ移行完了と呼ばない。

稼働中の旧JAR（SHA-256 `438736d1ff006691824ffb049fc06a0fe4c4dbbf9f7b312ecd941e7a2df9986e`）の実クラス/依存で、
新JARが作った所有合成DBを別JVMから読んだ。CC Checkpointは読めたがnative Checkpointは`CORRUPT_CHECKPOINT`で拒否された。
新JAR（`1c9886f9e99605a6407bd6b7d1bd3afbabd404def0b79c5b15610e14f326f483`）の対照では両方を読み、native historyを保持した。
これは実バイナリcomponentの互換試験で、旧アプリ全体の起動を検証した意味ではない。
少なくともこの旧JARへ、新しいnative未完了記録があるDBをそのまま渡す切戻しは禁止する。

切戻しは次の3つを分ける。

1. **API形式**: 新しいJAR/DBを維持し、今後のRunをCCへ戻す。chat/plannerのprofileを除去し、API/接続先/圧縮設定を保存した旧設定へ戻す。native未完了Runは変換せず保持し、元のnative構成を復元できる時に照合して再開する。
2. **アプリ版**: 観測した旧JARは新native Checkpointと非互換。その他の旧JAR/新DBの任意組合せもUNVERIFIED。新JARを保つAPI切戻しを優先し、版を戻す場合は対応する停止時backupを別の空領域へ復元する計画をレビューする。
3. **DB**: 全writer停止後の検証済み一式を復元する。SQLite本体だけをWAL/関連ファイルから切り離してコピーしない。新規データのある領域へ上書きしない。後続の外部副作用はDBを戻しても取り消せないため台帳を照合する。

## 段階的導入と停止条件

本番への停止/再起動・JAR/クライアント配置・外部YAML・DB backup/migration・業務データ試験は、元依頼§8.7の明示承認後に行う。
この文書とartifact/config差分が揃うまで本番適用の承認を求めない。

1. ソースのPR/全head CI/通常merge/exact SHA CIを確認し、JARのSHA-256・JDK25・SDK版・設定差分を固定する。
2. 承認後、既存Run/承認待ち/Checkpoint/不明副作用を照合し、全writerを停止する。旧JAR・外部設定・data-dir一式の検証済みbackupを保存する。
3. 別の空data-dirで復元と起動を検証してから、承認された配置先へ反映する。APIキーをCLI引数に置かず、所有する合成ツールだけの小さな新規Runから開始する。
4. 最初の段階は1 Run、1ツール効果、取消/再開を確認し、人手で結果と費用・usage予約を照合する。許可された新しいAPI予算・期限の中だけで行う。
5. 問題がなければ所有する限定作業へ広げる。実業務データの外部送信は別途明示範囲内とし、未検証feature/modelへ拡張しない。

既存のRun/AgentEvent・固定カテゴリ診断・numeric usageを使い、API/Run失敗率、rate limit、timeout/retry、出力枯渇、stagnation、
承認/Checkpoint再開、reasoning replay、payload保存/復元、GC、DB/WAL・payload容量、推論/cached、不明usage予約、時間・費用を監視する。
可観測なTTFTと総時間を分け、未測定を0にしない。生の平文/暗号化reasoningやキーを通常ログへ出さない。
互換性/owner不一致、必要payload欠損、未承認効果・成功prefix再実行、usage欠損の予約解放、保存/GCエラー、予算超過を1件でも検出したら拡大を止める。
認証・非対応設定・繰返すtimeout/出力枯渇/不要ツール再実行も停止・調査対象。API形式の新規Run切戻しを優先し、未完了nativeをCCとして再開しない。

## TDDとローカル品質

| 境界 | Red | Green |
| --- | --- | --- |
| 構成の明示profile・完全snapshot | `ac3a78980`、18件中3 assertion failure、error0 | `0508c7a4a`、関連31件成功 |
| 要求Options/子モデルの未検証変更 | `13539875d`、16件中15 assertion failure、error0 | `32790468b`、関連179件成功 |
| 既存CC thinking設定の継承 | `539420d22`、2件中1 assertion failure、error0 | `a7c766443`、null解除後2件成功 |
| nullable metadataの再取得 | SpotBugs1件、解析error0 | `148f03cc1`、単一snapshotに修正、抑制なしで0件 |

新規38件・Architecture7件の最終対象45件、既存native169+実アプリfixture1件、移行関連51件、Python10件が成功。
これらは重複を含む関連確認であり、件数を足して全回帰にしない。
最終全回帰は**8354件 / 成功8342 / 失敗0 / error0 / 期待skip12 / 新規skip0**。
Checkstyle0 / SpotBugs0 / analysis error0 / missing classes0 / Spotless成功。通常CIで実API/鍵を要求しない。
最初の移行fixtureの型合わせ・手動class path配線のコンパイル不備は、製品不具合のRedに数えない。
品質指摘の修正前に始めたfullは所有Mavenだけを停止して部分ログを保持し、上記は修正後の完了実行である。

## 証拠と本番適用待ち

| Phase | PR / merge | 全回帰 | exact merge CI / cleanup |
| --- | --- | --- | --- |
| 7 | [#635](https://github.com/mikoto2000/rei/pull/635) / `6e104b3d434d22873c8f45083d78063c07d0edb2` | 8301 / 成功8289 / skip12 / failure・error0 | 全成功 / 完了 |
| 8 | [#636](https://github.com/mikoto2000/rei/pull/636) / `64e2d4095154ac5a4f4b43fa3ff578e69573dfe1` | 8304 / 成功8292 / skip12 / failure・error0 | 全成功 / 完了。最初のBのCI制限時間超過は同一SHAの再試行成功として記録 |
| 9 | [#637](https://github.com/mikoto2000/rei/pull/637) / `18fb7d27ef0c98f337ad3be9647ddb0dd364ba2c` | 8316 / 成功8304 / skip12 / failure・error0 | 全成功 / 完了。ローカル実調査FAILEDは維持 |
| 10 | [#638](https://github.com/mikoto2000/rei/pull/638)と外部最終報告を参照 | 8354 / 成功8342 / skip12 / failure・error0（ローカル） | Git/CIの確定値は当該PRに記録。本番適用は未承認 |

所有記録: `F:/project/rei/target/responses-api-workflows/phase7-10-20261125/`。
Phase7 PR635、Phase8 PR636、Phase9 PR637は通常merge/exact SHA全CI/専用worktree・branch削除まで完了。
Phase9の基準は8316件/成功8304/期待skip12/failure0/error0、品質0/0、全Client成功。
Phase10のPR・merge SHA・exact CI・後片付けは当該PRと外部最終報告へ記録し、配布JARのsource/artifact SHAは同梱manifestへ固定する。
JARは鍵なし・所有home/tmp/dataで`--help`成功、検証ハーネスclassを含まないこととSDK版を照合した。本番JARへの配置ではない。
raw reasoningは所有SQLiteに限定し、ACLと2026-12-09までの手動保持方針を維持する。自動削除や常駐監視は設定していない。
