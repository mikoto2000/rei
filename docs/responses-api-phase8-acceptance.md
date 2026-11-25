# Phase 8 ユーザー指定ローカル互換サービスの受入

対象: `http://gx10-707e.local:8888`、`deepseek-v4.1-flash-1017`。
ユーザーが明示した接続先を検証する。DeepSeek公式 `https://api.deepseek.com` / `deepseek-flash`
とは別サービス・別モデル識別子であり、公式APIの受入成功とは扱わない。
正式なPhase8作業は、Phase7の通常merge `6e104b3d434d22873c8f45083d78063c07d0edb2`、
exact SHA全CI成功、専用worktree/作業branch削除を確認してから開始した。

## 隔離と承認枠

Phase7と同じ外部証拠領域・永続台帳を引き継ぐ。新worktreeで時計や要求数をリセットしない。
初期7要求と追加枠は分離し、追加200要求・入力100万/出力30万・3時間・ローカルAPI課金なし。
追加枠の開始は約09:27 JST、期限は約12:27 JST。試験は合成データと所有するtoolだけ。
キーは `REI_OPENAI_API_KEY`、未設定時はユーザー指定の `dummy-key`。OpenAIキーはローカル子JVMへ渡さない。
本番設定、履歴、DB、Scheduler、外部連携、サーバーポートを使用しない。
全体のSpring Boot起動・長時間RunはPhase9に残り、本試験をアプリ全体の受入へ読み替えない。

未検証の能力は明示的なcandidate profileだけでプローブする。
profileは構成時に固定し、実行中のendpoint snapshotを書き換えない。
candidateの宣言は提供元能力が確認済みという意味ではない。通常profileへ暗黙昇格しない。

共通ハーネスのOpenAI経路は、型付きoptionsで `service_tier:default` を固定し、
他tierを予約・送信前に拒否する。ローカルにはこのパラメーターを追加しない。
TDD: Red `959f03f2f` は期待するfailure2/error0、Green `6355143ca` と既存meterを合わせ8件成功。
その後、stream経路の同条件と原prompt/context不変も確認した。台帳の金額・時間・bindingは変更しない。

## 先行結果の引継ぎ

Phase7でローカルへ初期7＋追加14要求を実施した。
通常/stream、平文推論、SQLite原本一致、別JVM再送、実Core、動的選択、承認/拒否とCheckpointが成功。
JSON Schemaの初期2要求はHTTP400で、usage不明を0へ置換していない。
追加14要求はinput19,979/output1,528/reasoning1,034/cached8,192で既知。
これらはローカルの結果だけであり、同名モデルの公式APIへ転用しない。

## 独立能力検証（2026-11-25 11:20 JST）

| 能力 | 結果の扱い |
| --- | --- |
| effort low | 中国剰余計算7417の正答、平文推論902文字・reasoning390を観測 |
| effort none | reasoning part文字数0・reasoning tokens0を観測。ただし要求した最終回答形式が一致せず、課題成功とは扱わない |
| JSON Mode | 同じ非JSON指示をformatなしで受理。`json_object` だけを指定するとHTTP400・固定診断語 `not supported`。拒否を記録し後続の同一反復を中止 |
| JSON Schema | `json_schema`、strict、整数enum・余分なfield禁止の指定がHTTP400。同じく非対応診断語。成功扱いしない |
| 合成画像 | 初回RED後、事前固定したRGB3試行すべて正答。32×32 PNGを認識。一般の画像理解全般の品質は未評価 |
| reasoning summary | auto指定をHTTP200で受理し計算162153も正答したが、summary文字数0。生成の有効性はUNVERIFIED_OR_IGNORED、対応済みへ昇格しない |
| 出力枯渇 | 独立上限32でLENGTH・input49/output32/total81。reasoning/cachedの詳細は欠損のまま保持 |
| 継続性 | A原本/C既存実要約が各3/3で識別子と7417→7418を保持。D独立動的選択3/3、専門tool各1回・metadata保存。B省略対照は未実施 |

`none` の適用と計算課題の成功は異なる条件である。一度の回答形式不一致から
APIの推論OFFが無視された、または一般に計算能力がないとは判断しない。
JSON系のHTTP400は、この接続先・モデル・送信形式での拒否であり、DeepSeek全体の機能非対応とはしない。
refusal、欠損推論、異owner/endpoint、GC、切断/cancel、引数・snapshot・部分call拒否などは
既存のnative transport/replay fixtureで補完し、自然な不正応答や過負荷の誘発を要求しない。

## 要求数・usage・性能

正式Phase8の新規要求は32（HTTP200×30、HTTP400×2）。初期7は別枠。
追加枠全体では46/200要求、完了44・usage不明2。
既知小計はinput47,169/output6,678/total53,847、reasoning4,545/cached26,624。
上限枯渇1件のreasoning/cached詳細とHTTP400の2件は不明なので、これらの全件合計は確定しない。
不明2要求にはinput17,852/output4,096を予約したまま保持している。
精算済み使用量と予約を合わせた予算消費はinput65,021/output10,774、API課金0。
失敗したnone課題や拒否された設定も除外・リセットしない。

Aの継続requestはinput186/184/190、output144/184/80、4.916/5.218/2.674秒。
Cの継続requestはinput242/222/213、output67/70/78、要約と継続の合計19.991/16.265/12.985秒。
要約は501/489/428bytes。この短い課題ではCの最終inputもAより大きく、追加要約要求も必要だった。
品質向上・性能改善を認定せず、そのまま結果を残す。

## Phase7のusage詳細追記

OpenAI追加65要求: input65,391/output4,222/total69,613。
reasoningは既知小計2,226、cachedは既知小計42,907。上限枯渇の2要求では両詳細が欠けるため
全件合計は不明とする。初期のcached0とは別であり、差し引いて実費を確定しない。
キャッシュ命中の報告は取得できたが、同条件の対照によるTTFT改善や料金照合の証拠ではない。

## 現在の境界

本番Responses gateは閉じている。受入結果は接続先・モデル・能力別に扱い、
未確認設定や拒否された設定を本番profileへ追加しない。API形式のfallbackはない。
ローカル受入fixture25件は全成功（failure/error/skip0）。追加Javaテスト3件で全Backendは8,304件を見込むが、実CIで照合する。
sourceの全回帰・品質・Client CI、通常merge、exact merge SHA CI、worktree削除が完了するまで
Phase8全体を完了としない。

公式仕様照合（2026-11-25）:
[DeepSeek Responses](https://api-docs.deepseek.com/guides/responses_api/)、
[Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)。
公式のsemantic SSEとplain reasoningの契約を参照したが、ローカルの能力判定には実観測を使う。
