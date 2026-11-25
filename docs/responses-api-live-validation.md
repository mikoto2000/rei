# Responses API 実接続受入試験

更新日: 2026-11-25（Asia/Tokyo）。基準: `38a13f1d38f0759049290e37df40cdd078a58e54`。
Spring AI 2.1.0 / OpenAI Java SDK 4.101.0。依存更新、本番設定・DB・プロセスの変更は行わない。

この文書は Phase 7〜10 の進行記録である。下記の初期実接続試験を実施したが、フェーズ全体の
受入は未完了。fixture 成功と、指定した実接続先での成功を区別する。
本番 factory の gate と、fixture factory のローカル接続先制限を維持する。

最新の実Core、承認/拒否、Checkpoint、構造化/画像/出力上限、3試行の継続性結果は
[Phase7受入記録](responses-api-phase7-acceptance.md)を参照する。
指定ローカルの追加検証は [Phase8受入記録](responses-api-phase8-acceptance.md)。
下記の初期試験・準備開始時点の表は履歴であり、現在の受入状態とは区別する。

## 初期実接続試験（2026-11-25）

対象の本体コードは main `38a13f1d38f0759049290e37df40cdd078a58e54`。
ユーザーが接続先・モデルを指定し、各10要求・入力10万/出力2万・15分、OpenAI USD2、
ローカルAPI課金なし、合成データだけの送信を承認した。キーは明示した環境変数から
別JVMへ注入し、設定ファイル全体・本番環境変数・利用者データを継承しない。

| 項目 | OpenAI | ユーザー指定ローカルDeepSeek互換サービス |
| --- | --- | --- |
| 接続先 | `https://api.openai.com` | `http://gx10-707e.local:8888` |
| モデル | `gpt-5.6-sol` | `deepseek-v4.1-flash-1017` |
| 推論設定 | 通常low、ツール課題medium | 提供先の既定値 |
| 要求数 | 7（HTTP200×7） | 7（HTTP200×5、HTTP400×2） |
| 非stream / stream通常会話 | PASSED | PASSED |
| SQLite原本一致 / 別JVM履歴再送 | PASSED | PASSED |
| 推論の取得・再送受理 | encrypted、別JVM保存復元後の再送もPASSED | plaintext、tool往復と別JVM再送がPASSED |
| Function Calling | 既存ToolCatalog・READ権限・ToolLoop・保存・Run Budget、操作1回 | 同左、操作1回 |
| JSON Schema | PASSED、整数型と余分なfieldなしを検査 | FAILED、HTTP400。固定語診断に非対応メッセージ。詳細な能力境界は継続調査 |
| Input / Output / Reasoning | 559 / 178 / 123 | 確認済み912 / 414 / 384、400の2要求はusage不明 |
| Cached Tokens | 0、短い試験でcache性能を評価しない | 確認できた値0 |
| 可視本文のTTFT（1標本） | 1521.189ms | 928.1236ms |
| API料金 | usageに基づく通常単価推定USD0.005796、請求未照合 | ユーザー確認によりAPI課金なし |

OpenAIでは、ツール課題の最終応答に2596 UTF-8 bytesの暗号化推論を取得し、SQLite保存後、
別JVMで所有者とendpointを照合して1件を再送した。HTTP200と正解7418を確認した。
payload本文は報告せず、ハッシュ・件数・サイズで照合した。平文推論も全文をログへ出していない。

これは単一の初期試験である。動的公開・承認継続・Checkpoint・Goal・SubAgent・長時間Run・
圧縮・異常系・画像・意味的推論継続性の比較は未完了。ローカルの結果をDeepSeek公式APIの
対応判定へ流用しない。SDK単体/既存BoundedToolLoopを経由した結果を実アプリ全体の受入に読み替えない。

試験ハーネスの読取カーソルを開いたままPayloadStoreの検証を呼び、SQLiteの書込検証と競合する
問題を外部通信なしのfixtureで再現した（1 assertion failure、error0）。カーソルを閉じてから
正規store.loadを呼ぶ最小修正後、予算5件と復元1件が成功した。本体の拒否契約は変更していない。
失敗した2回のハーネス復元試行はHTTP送信前に停止しており、要求数へ加算しない。

ユーザーは続く受入群について、各接続先に**追加200要求・入力100万/出力30万・3時間、
OpenAI追加USD20、ローカルAPI課金なし、最大50往復のLongを含む合成データ試験**を承認した。
初期の台帳をリセットせず、追加枠を別台帳で追跡する。本番適用の許可ではない。

## 準備開始時点の状態（履歴）

| Phase | ソース作業 | 実 API 受入 | PR / マージ | 本番適用 |
| --- | --- | --- | --- | --- |
| 7 OpenAI | IN_PROGRESS（計画検査と試験設計） | BLOCKED | 未作成 / 未マージ | 未承認・未実施 |
| 8 DeepSeek | NOT_STARTED（独立した試験計画のみ） | BLOCKED | 未作成 / 未マージ | 未承認・未実施 |
| 9 長時間 Run | NOT_STARTED（シナリオ設計のみ） | NOT_STARTED | 未作成 / 未マージ | 未承認・未実施 |
| 10 本番利用準備 | NOT_STARTED | UNVERIFIED | 未作成 / 未マージ | 未承認・未実施 |

ユーザーが指定した設定パスの `Rai` にはファイルがなく、`Rei` の同名ファイルを読み取り確認した。
現在のチャットはローカル互換サーバーであり、両社の公式 Responses 接続設定ではない。
OpenAI 公式キーは画像生成設定に存在するが、別用途の試験への転用・対象モデル・予算は未確定。
DeepSeek 公式キーの取得先も未確定。キー値を作業領域、manifest、ログ、PR に複製しない。
本番の設定全体や環境変数全体を検証プロセスへ読み込ませない。

## 課金前の計画検査

`scripts/responses_live_preflight.py` は標準 Python のみで実行できる**オフラインの計画検査**。
ネットワーク、認証情報取得、Spring 起動、DB 接続、ファイル変更を行わない。
承認の真正性、モデルの利用権限、API 能力を証明するものではない。
また、実行時予算ガードや隔離済み実 API harness の代わりにはならない。

1. `config/responses-live-plan.example.json` を Git 管理外の証拠領域へコピーする。
2. ユーザーが承認したモデル、推論設定、予算、試験 ID と反復回数を記入する。
   `authorization_reference` は承認を記録したチャット・文書の参照。記入だけで承認されたことにしない。
3. 秘密値は書かず、`credential_env` に後で隔離プロセスへ明示注入する環境変数名だけを書く。
4. 計画を検査し、出力と対象 commit、manifest の SHA-256 を証拠として保存する。

```powershell
python scripts/responses_live_preflight.py ABSOLUTE_PATH_TO_PLAN.json
python -m unittest discover -s scripts -p test_responses_live_preflight.py
```

例はモデル・金額・上限が空であり、そのままでは必ず拒否される。
USD の金額・単価は小数文字列、回数・時間・トークン数は正の整数を明示する。
時間の単位は秒。単価は100万トークン当たりの上限であり、課金に関係する
cache write / cache hit / context tier 等の**該当し得る最大単価**を使う。
現在の料金を別途公式ページで確認し、ユーザーが承認した費用上限内に収める。

各試験の予約要求数は `repetitions × max_calls`。入力・出力はそれぞれこれに
`input_tokens_per_call` / `output_tokens_per_call` を掛け、全試験分を合計する。
推論トークンは出力予約に含める。履歴再送・tool definitions・要約・子 Run・retry の要求も
実際の計画へ含める。最悪予約金額を超える計画は拒否する。成功時の状態は `PLAN_VALIDATED`、
受入状態は常に `UNVERIFIED`。価格・usage の実測や費用保証として解釈しない。

公式 OpenAI / DeepSeek の URL と再送方式の組を明示して検査する。別サービスの同名モデルや
任意 URL を自動認定しない。重複 JSON キー、未知フィールド、秘密値用フィールド、非有限値、
曖昧な型、重複試験 ID、不足した上限を拒否し、診断へ入力値・ファイル本文を含めない。

## 実行前に必要な隔離と harness

現在の `LlmModelProvider.forResponsesFixture` は loopback / `.invalid` のみ許可する。
これをプロキシ等で外部へ転送して実接続可能にしたり、制限を削除したりしない。
現状の本番起動パラメーターだけでは実接続受入を開始できない。
必要最小限の試験専用構成を実装・レビューしてから課金試験へ進む。

| 対象 | 実行前の条件 |
| --- | --- |
| プロセス | allowlist で構築した子環境、明示した config location、独立 port、上限付き正常停止・強制停止 |
| データ | 新規一時 root 内の SQLite・会話・Checkpoint・承認・payload・操作台帳・work・ログ・capture |
| 外部機能 | Scheduler・外部連携・通常 capture を停止し、合成データと所有済み試験 callback のみ登録 |
| 接続 | 承認済み公式 HTTPS origin・model・reasoning・fingerprint を固定。redirect や別 API への fallback を許さない |
| 予算 | 実送信直前の要求数・入力予約・出力上限・金額・残時間を照合。usage 不明時は残額を推測せず停止 |
| 実行 | 既存 ToolCatalog・ToolLoop・権限・承認・Run Budget を通す。別の agent / retry loop を作らない |
| 保存 | 理由本文は保存領域だけへ。通常ログにはハッシュ・サイズ・件数・順序・数値 usage のみ |

`ChatExecutionService` を含む実アプリ検証は Phase 9 で必要。
SDK 単体 smoke の成功を Goal・承認再開・SubAgent の成功として扱わない。
事前計画検査には実行時ガードを実装していないため、その出力を根拠に未完成の harness を動かさない。

## 基本試験の台帳

対象モデル・推論設定・日時は未確定。以下の各行は実 API に対する状態である。
各実施結果に対象 SHA、endpoint fingerprint、モデル、SDK、日時、試行数、判定と証拠を付ける。

| ID | 実 API で確認する合格条件 | 状態 |
| --- | --- | --- |
| OA-01 | 非 stream 応答が指定した合成課題を満たす | BLOCKED |
| OA-02 | stream が completed 終端、本文と数値 usage を復元 | BLOCKED |
| OA-03 | store:false の履歴再送で複数ターンの事実を保持 | BLOCKED |
| OA-04 | System instructions の機械判定条件を保持 | BLOCKED |
| OA-05 | tools / tool_choice のない要求を受理 | BLOCKED |
| OA-06 | 明示 max_output_tokens を受理・適用 | BLOCKED |
| OA-07 | モデルが対応する明示 reasoning effort を適用 | BLOCKED |
| OA-08 | 正常・不完全・失敗の terminal status を区別 | BLOCKED |
| OA-09 | input/output/total/cached/reasoning を正規化、欠損を推測しない | BLOCKED |
| OA-10 | JSON Schema に一致する structured output | BLOCKED |
| OA-11 | JSON mode に対する構文・内容検証 | BLOCKED |
| OA-12 | 所有する合成画像の内容を正しく認識 | BLOCKED |
| OA-13 | refusal から callback を実行しない | BLOCKED |
| OA-14 | 出力枯渇で部分 callback を実行せず usage を保持 | BLOCKED |
| DS-01 | 非 stream の平文推論全文を取得 | BLOCKED |
| DS-02 | stream delta/done/terminal から全文を重複なく復元 | BLOCKED |
| DS-03 | 複数 reasoning item の件数・順序を保持 | BLOCKED |
| DS-04 | id / status を保持 | BLOCKED |
| DS-05 | MessagePart の順序を保持 | BLOCKED |
| DS-06 | Function Call と reasoning の順序を保持 | BLOCKED |
| DS-07 | plaintext content を次要求で受理 | BLOCKED |
| DS-08 | SQLite 保存・復元後の再送を受理 | BLOCKED |
| DS-09 | 別プロセスで復元した再送を受理 | BLOCKED |
| DS-10 | 承認中断・再起動・承認継続で成功済み操作を重複実行しない | BLOCKED |
| DS-11 | Checkpoint 再開で成功 prefix を保持 | BLOCKED |
| DS-12 | 正規圧縮後の推論・直前 tool output を保持 | BLOCKED |
| DS-13 | 明示した推論 OFF の対応範囲を確認 | BLOCKED |
| DS-14 | 必須 reasoning 欠損時は次要求・callback 前に安全停止 | BLOCKED |

DeepSeek にも OA-01〜14 相当を `DS-COMMON-01`〜`14` として別結果で記録する。
未対応は公式根拠と対象モデルを明記して `UNSUPPORTED` にする。未実施の `UNVERIFIED` と区別する。
自然発生を要求しない refusal / rate limit / 不正応答は、実通信契約と fixture による補完を記録する。
故意の大量要求で障害を誘発しない。

### 推論・ツール・耐久性の追加群

以下は準備開始時点の `REPLAY`, `TOOLS`, `DURABLE`, `FAILURE`, `QUALITY`, `PERF` の計画。
現在の実施結果と残件は上記の最新受入記録で追跡する。
暗号化 payload は復号・変更せず、ハッシュ・サイズ・順序で照合する。
平文推論は plaintext のまま保持し、全文を PR・通常ログへ出さない。

- REPLAY: encrypted include、複数 item、item id / call_id、tool call/output 順序、
  原本との一致、再送受理、SQLite と別 JVM からの復元、別 endpoint / owner の拒否。
- TOOLS: 31基本＋searchTools、専門追加の次要求への反映、上限、LRU、引数検査、並列 call、
  連続往復、元要求 snapshot、ToolResultMetadata。所有するカウンター・一時ファイルだけを使う。
- DURABLE: 成功 prefix → 承認中断 → 保存 → 正常停止 → 同じ検証 DB で起動 → 承認 → 継続。
  拒否、Checkpoint、結果保存後再起動、接続変更、参照欠損、GC 保護も個別判定する。
- FAILURE: 認証、モデル、非対応設定、枯渇、timeout、切断、キャンセル、SDK例外、
  不完全 JSON、保存失敗、予算超過。fixture 注入と実接続を区別する。
- QUALITY: A 正規再送、B 提供元仕様が許す場合だけ再送なしの対照、C 完了文脈の正規要約、
  D 通常機構による instructions / tools 更新。試行数を承認予算内で先に固定する。
  正解付き課題で事実・指示・操作認識・不要な再実行・成果物精度を評価する。
  単一成功や文章一致を意味的継続性の証拠にしない。差がなければそのまま記録する。
- PERF: 要求数、全 usage、TTFT、総時間、tool時間、payload save/load、累積bytes、
  DB/WAL、wire JSON bytes、heap、予約額・推定額・確認可能な実費用。
  TTFT は最初の非空ユーザー可視テキストまでと定義し、reasoning 到着時刻は別記する。

### Phase 9 の独立した準備

| シナリオ | 合成課題と完了条件 | 状態 |
| --- | --- | --- |
| A 調査 | 固定した欠陥を含む小規模 repo。対象網羅率・正答/誤答・根拠・再読込回数を採点 | NOT_STARTED |
| B Goal | 3成果物の内容・hash と Goal/計画/永続予算を照合 | NOT_STARTED |
| C 承認 | prefix と承認後のカウンターを各1回、別プロセスで継続・参照解放を照合 | NOT_STARTED |
| D 圧縮 | 複数圧縮を起こし、全直前 call/output と中間事実、参照を確認 | NOT_STARTED |
| E 子 Run | 子の独立 owner / endpoint / 権限 / 予算、結果検証、失敗・取消を確認 | NOT_STARTED |
| F 障害 | stream・tool前後・payload前後・Checkpoint後で停止し、台帳と不明状態を照合 | NOT_STARTED |

各社の Smoke → Short（5〜10往復）→ Medium（20〜30）→ Long（50以上）を段階的に評価する。
Long / Extended は明示された時間・費用枠の範囲内でのみ実施し、予算不足を勝手に補わない。
既存 CC 比較も別の接続条件・費用承認が必要。120ラウンド fixture の結果を実 API 負荷に読み替えない。

## 公式仕様との照合

2026-11-25 に参照。これは資料上の対応であり、アカウント・モデルの実受入結果ではない。

- [OpenAI reasoning](https://developers.openai.com/api/docs/guides/reasoning): stateless な
  encrypted reasoning の再送契約を確認する。モデル固有の effort / summary は選定後に照合する。
- [OpenAI prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching):
  rendered prefix の一致・最小長・モデル別の価格条件を確認する。動的 tools / instructions の
  更新は cache の比較条件へ記録し、短い smoke の cache miss を不具合と断定しない。
- [DeepSeek Responses](https://api-docs.deepseek.com/guides/responses_api/):
  平文 content と semantic SSE、無視されるパラメーター、モデル別能力を個別確認する。
  Structured Output と画像の公式対応記載だけで、Rei または対象アカウントでの成功を認定しない。

SDK 公開型と実JSON/SSE の一致を fixture で確認し、非対応項目の黙示無視を
「適用された」と解釈しない。業務機能に provider 判定を分散させない。

## 証拠と次工程

作業証拠: `F:/project/rei/target/responses-api-workflows/phase7-10-20261125/`。
生成した worktree の外に保管する。許可のない生APIデータ、キー、推論全文は格納しない。
今回の preflight はキーを読まず、合成 fixture だけで検査する。

次は不足条件の確定、試験専用 harness・実行時ガードの実装、実接続受入、全回帰・品質、
Phase 7 PR、最新 head CI、通常 merge、exact SHA push CI、証拠退避、通常 cleanup の順。
Phase 7 のマージ状態と実受入状態を確定する前に、依存する Phase 8〜10 を完了扱いしない。
一方の API が止まっていても独立した準備を継続する。
本番 gate は接続先ごとの実受入・長時間 Run が完了するまで閉鎖する。
