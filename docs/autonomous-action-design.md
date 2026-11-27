# 自発的行動・Bluesky 投稿案 — Phase 0 設計と安全性ゲート

調査日: 2026-11-28 (Asia/Tokyo)。調査対象: `origin/main` の
`9f2bd5b698708eceb9be1f80b58912503d239799`。
これは設計資料であり、実装済み機能の利用案内ではない。

## 結論と進行条件

**安全性ゲートは未通過。後続フェーズは開始せず、機能を有効化しない。**
既存 API 認証・Tool 承認では、人間による外部公開承認と、モデルが起動した
同一 OS ユーザーのプログラムによる操作を区別できない。
既存の Bluesky 直接投稿も外部公開専用の承認を持たない。
依頼の第15・21・24節の停止条件に該当する。

これは悪用を実証したとの主張ではなく、コードから確認した権限境界の欠落である。
実際の API キー・アプリパスワードの読み取り、実アカウントへの投稿、
既存ユーザー設定の変更は行っていない。

解消には、承認だけでなく認証情報・永続状態・外部送信の保護を含む境界が必要。
同じ OS 権限で任意コードを実行できるモデルに対し、Java の型、ThreadLocal、
HTTP ヘッダー、秘密ファイル名だけでは境界にならない。
人間の操作を信頼できる構成を確定し、その侵害を模した否定テストを通してから進む。

## 調査範囲と既存実装

ルートおよび上位、追跡ファイルに `AGENTS.md` は見つからなかった。
`DEVELOP.md`、ストレージ・Web API・設定資料と以下の実装を調査した。

| 対象 | 調査結果・再利用方針 |
| --- | --- |
| `bluesky/BlueskyPostTools.java` | `blueskyPost` をモデルに公開。自発生成には絶対に登録しない。既存通常投稿の保護も必要。 |
| `bluesky/BlueskyPostService.java` | 有効設定・資格情報・本文を検証して送信。承認主体・リビジョン・ハッシュ・rkey は扱わない。新規承認投稿で `post(text)` を直接使わない。 |
| `bluesky/DefaultBlueskyApiClient.java` | `createPost` は送信時に createdAt と facets を生成し、メンションを解決する。確定レコード送信には不適合。認証・JSON・HTTP の実装経験を再利用し、別の厳格な送信契約を追加する。 |
| `bluesky/BlueskyReplyTextGenerator.java` | 返信用コンテキスト・会話は公開許可済みではないため投稿案生成に流用しない。 |
| `llm/LlmChatClientProvider.java` | 通常 client は system prompt、memory、runtime、working set、task state 等の advisor を付与。`BLUESKY_REPLY` も一般コンテキストを付与し得る。専用 client が必要。 |
| `llm/LlmModelProvider.java`, `LlmFeature.java` | 接続先解決と機能別モデル選択を再利用。新用途 `autonomous-bluesky` を追加し、プロバイダ固有の structured output を必須にしない。 |
| `temporal/PersistentAgentScheduler.java` | 永続 claim、cron、zone、取り逃しをまとめる既存実装がある。投稿を通常の会話 Run として dispatch しない。 |
| `temporal/AgentScheduleDispatcher.java` | 既存 clock は `SecretaryTick` を実行できる。この clock で次の検討時刻だけを確認する専用 tick を接続する。LLM 呼び出しは tick スレッドから bounded worker に移す。 |
| `bluesky/BlueskyReplyScheduler.java` | AtomicBoolean はプロセス内排他のみ。生成上限・機会 ID の永続 claim の代用にしない。 |
| `attention/AttentionService.java`, `AttentionRepository.java` | 永続通知とイベント配信を再利用。現行の run/secretary 前提に draft の参照種別と安定 ID を追加する必要がある。 |
| `artifact/ArtifactStore.java` | Project/Session/Run に所有権を持つ不変成果物。自発投稿に仮 Run を作らない。初期版の短い本文と revision は同一 SQLite transaction に置き、ArtifactStore は利用しない。 |
| `storage/StorageMigrationConfiguration.java`, `StorageMigrationCoordinator.java` | 通常 Bean 初期化より先に移行・バックアップ・プロセス排他を実施。新テーブルをこの移行機構に登録する。 |
| `web/SecurityConfig.java`, `ApiKeyAuthenticationFilter.java` | stateless bearer。一致したキーを持つ全クライアントが `api-client`。個人・操作経路の真正性は保証しない。 |
| `client/src/features/attention/Attention.tsx` | 一覧・選択・詳細・操作中の排他を再利用。承認専用の検証を一般 workspace mutation と混同しない。 |
| `terminal/cli/.../RemoteCommands.java` | 軽量 CLI の構造化コマンド入口。従来 Shell の独立した明示コマンドとともに共通 backend を呼ぶ。自然言語入力を承認に変換しない。 |

## 脅威モデルと未解決の重大事項

保護対象は、非公開の生成コンテキスト、投稿本文・宛先の完全性、人間の承認、
送信資格情報、監査、上限、外部副作用の一回性である。
公開情報源・LLM 応答・モデル生成コマンド・委譲先出力は信頼しない。

| ID | コード根拠と想定経路 | 必要な対策・判定 |
| --- | --- | --- |
| S1 | `ApiKeyAuthenticationFilter` は同じ bearer をすべて `api-client` に変換。`WebApplication` は環境由来キーを登録する。キーを利用できるプログラムは GUI と同じ API を呼べる。 | bearer だけで公開承認を成立させない。呼び出し元の自己申告 actor・GUI ヘッダーも不可。未解決。 |
| S2 | `ProcessLaunchService.prepare`、`BackgroundProcessManager` は通常の ProcessBuilder。環境をクリアせず、OS ユーザーも分離しない。`ExternalAgentProcessRunner.configureEnvironment` の既定実装も空。`REI_API_KEY`、`REI_BLUESKY_APP_PASSWORD` を環境で設定する構成では子へ継承され得る。 | 実行系から秘密と承認状態への OS レベルのアクセスを遮断する。環境変数の削除だけでは同一ユーザーの設定・DB・プロセスへのアクセス対策にならない。未解決。 |
| S3 | `FullAutoToolPermissionTest.startupFlagApprovesCommonToolsIncludingComputerUseAndUnknownCallbacks` が `blueskyPost`・`runCommand`・`computerUse` の自動承認を期待する。`ToolPermissionPolicy.evaluateCapabilities` もこれに対応する。 | 専用承認を通常 Tool 承認から分離。通常の画面操作で承認画面を押せる構成も防ぐ。通常投稿の互換性変更範囲の確定が必要。未解決。 |
| S4 | `BlueskyPostService.post` は有効設定と資格情報だけで公開可能。Shell/HTTP/外部エージェントが資格情報を使えばサービス自体を迂回できる。 | 生成 client から全ツールを除去するだけでは、他のモデル実行が人間を偽装する問題を解決しない。送信秘密を隔離し、未承認レコードを送れない出口に集約する。未解決。 |
| S5 | 通常および返信の ChatClient に公開未許可の context advisor が存在。 | 専用 client は新規構築し advisor・tools・memory・通常 system をゼロにする。capture/observation に秘密が入らないことも接続先別に検査。設計可能、未実装。 |
| S6 | 現行 createPost は rkey を指定せず createdAt/facets を送信時生成。通信結果不明時に同じ本文を再送しても別投稿になり得る。 | 事前に TID と完全なレコードを保存。結果不明時は照合のみ。設計可能、未実装。 |

### 検討した解決案

1. 同じ bearer に承認エンドポイントを追加: S1/S2 を満たさず不採用。
2. ローカル秘密・one-time nonce・ThreadLocal のみ: 同じ OS 権限の任意コードや
   UI 自動操作を防げず、単独の対策として不採用。nonce 自体は再送防止に有用。
3. 外部の人間確認付き署名器と、秘密・状態・ネットワーク送信を保護する実行境界:
   検討候補。署名対象は draft ID/revision/hash/DID/期限/challenge/record hash。
   WebAuthn の user verification 等を候補にできるが、GUI・Shell 双方の導線、
   enrollment、署名確認、既存直接投稿の移行、Windows/Linux の隔離と
   機器・運用要件が未検証。別プロセスを同じユーザーで起動するだけでは不十分。
4. 自発投稿の利用中は一般の command/HTTP write/delegation/computer use と
   直接投稿をアプリ全体で無効化: 互換性への影響が大きい。さらにファイル書き込み・
   起動済みプロセス・外部 MCP も含む迂回経路を閉じる必要があり、実証前に採用しない。

本 Phase は3または4を採用済みとは扱わない。実行環境・互換性の変更を含む
方式を確定し、S1〜S4 の否定テストを通過するまで安全性ゲートを閉じる。

## 後続の詳細設計案（安全性ゲート通過後に実装）

### 責務と情報フロー

`Opportunity -> policy -> public source snapshot -> isolated generator -> drafts -> attention`
を検討経路とする。別に `human authorization -> atomic publishing claim -> transport -> reconcile`
を置く。検討処理には transport、Tool callback、通常会話の runner を注入しない。
policy の `shouldConsider` は生成を許すだけで公開権限を持たない。

policy は機能フラグ・宛先設定・停止状態・pending 上限・日別上限・cooldown・
永続 claim を LLM より前に検査する。manual は cron の有効無効だけを迂回でき、
機能フラグや共通制約を迂回しない。SKIP は成功。失敗時の再生成ループを作らない。

### 設定と公開情報源

`rei.autonomous-action.enabled=false` と `bluesky.enabled=false` を既定とし、
cron は一日一回、zone は既存アプリの zone、draft 上限1/日、pending 上限1、
cooldown 1h、expiration 72h。regeneration は別に3回/案・cooldown 1h を既定候補とする。
cron/zone、正の件数、正の期間、prompt と source のサイズを起動時に検証する。
既存の設定管理に従い再起動反映とし、UI で動的更新が可能と偽らない。

初期情報源は専用の公開許可済み話題テーブルだけから取得する。
通常ナレッジ・会話・Activity・Reflection は検索自体をしない。
登録操作で公開利用の許可を明示し、id・本文・許可時刻・許可主体を記録する。
生成には許可された snapshot と公開済み投稿だけを上限件数・長さ付きで渡す。
見送り理由は既定非公開。別の明示的な公開許可を経た feedback だけを取得する。
取得後に LLM に秘密を除外させる方式は使わない。

generator は機能別 endpoint の素の model と tools を除いた options から
新しい ChatClient を組む。通常の system/advisor/Run context を再利用しない。
信頼しない source を JSON のデータとして渡し、固定の安全指示と変更可能な
投稿用 system prompt を分離する。source が指示を含んでもツール・データ取得能力は増えない。

応答を厳格に decode し、`SKIP` と `PROPOSE_POST` 以外を拒否。
空本文・欠落 draft・未知 source ID・余分な実行指示・過大応答を拒否。
Unicode 拡張書記素300、UTF-8 3000 bytes の双方を検証する。
初期レコードは `$type/text/createdAt` の allowlist とし、facets/entities/reply/embed を
生成も受領もしない。本文中のメンション形式も保守的に拒否し、handle 解決しない。
URL は文字列として許容し、リンクカード取得を行わない。
公開済み本文の正規化一致と文字 n-gram の類似度で重複を抑制し、閾値を fixture で検証する。

### 永続化と競合

storage.db に専用テーブルを追加し、migration coordinator と object registry に登録する。
投稿案の内容・状態・revision・上限判定・opportunity claim は同じ transaction 境界に置く。
この選択は S2 の OS 隔離がない状態での DB 改ざん耐性を意味しない。

| テーブル案 | 内容・制約 |
| --- | --- |
| autonomous_opportunities | id unique、trigger、scheduled slot、開始/終了、policy reason、claim token、LLM 結果。scheduled slot の unique で同じ機会を再生成しない。 |
| autonomous_drafts | id、type、opportunity unique、生成契機、生成/更新/期限、DID/handle、current revision/state、見送り理由と公開可否、再生成回数と最終試行。 |
| autonomous_draft_revisions | draft/revision unique、本文、SHA-256、reason、source snapshot IDs、生成方式と日時。不変の過去 revision を保持。 |
| autonomous_public_sources | 公開許可済み本文、許可主体・日時、失効。通常ナレッジとは分離。 |
| autonomous_publish_attempts | draft、承認 revision/hash/DID、承認主体・証拠参照・日時、rkey、凍結した全 record JSON と hash、開始/結果/URI/CID/失敗分類。DID/collection/rkey unique。 |
| autonomous_attention_outbox | draft ID、通知 ID、配送/解消状態、再試行時刻。保存と同一 transaction に挿入。 |
| autonomous_audit | 機会・状態遷移・承認拒否・照合を ID と reason code で記録。本文・秘密・system prompt は一般ログに出さない。 |

短い SQLite writer transaction で pending/日別件数/cooldown を読み claim する。
LLM 中は transaction を保持しないが、durable active claim は維持する。
完了時に claim 所有者・停止状態を再検証して保存する。再起動時の残存生成 claim は
中断として終端化し、自動で LLM を呼び直さない。失敗/SKIP も試行 cooldown を記録する。
生成日と上限の基準 zone を記録し、再起動で上限が消えないようにする。
再生成は別の durable claim と回数予約を取り、失敗も試行上限に含める。
編集は生成数に含めず、本文検証後に新しい revision を追加する。

### 状態・承認・結果照合

PENDING_REVIEW は編集・再生成・見送り・期限切れ・承認開始だけを許す。
FAILED の再試行は新しい明示承認が必要。REJECTED/EXPIRED/PUBLISHED は再投稿不可。
PUBLISHING/UNKNOWN は編集・無条件再送不可。独立した APPROVED は追加しない。

公開時は信頼できる人間の証拠を検証し、id/state/revision/hash/DID/期限/設定版を
条件付き更新で照合する。同一 transaction に承認記録・rkey・全レコードと
PUBLISHING を保存してから一度だけ送る。承認後に LLM を呼ばず、レコードを再構築しない。
送信セッションの DID が一致しない場合は送らない。資格情報の更新で宛先を変えない。

公式 createRecord はクライアント指定 rkey を受け付ける。
feed.post は `key=tid` のため UUID をそのまま rkey に使わない。
一般的な rkey の構文だけで妥当性を判定しない。
transport の暗黙再試行を禁止し、接続例外・応答喪失は保守的に UNKNOWN とする。
送信前の検証失敗など、未送信が確定した場合だけ FAILED。
成功応答の URI が DID/collection/rkey と一致し、CID が有効な場合だけ PUBLISHED。

起動時の PUBLISHING は UNKNOWN に移して、getRecord のみで照合する。
URI、DID、rkey、保存した全 record を比較し、一致すれば PUBLISHED。
異なる record、RecordNotFound、削除、読み取り障害は UNKNOWN のまま保持する。
未投稿が別の確かな証拠で確定しなければ FAILED にしない。
再承認で再試行するときは同じ rkey・同じ record。putRecord は使用しない。
これは exactly-once の保証ではなく、同じ外部識別子を使い不確定時の送信を止める方式。

### UI・通知・時刻・履歴

GUI/従来 Shell/軽量 CLI は共通 backend の一覧・詳細・編集・再生成・見送り・照合を使う。
詳細には全文、DID/handle、reason、source、日時、trigger、revision、期限、結果・失敗を表示。
承認前に最新全文と宛先を表示し、その snapshot に署名等の人間確認を結び付ける。
編集/再生成の応答は新 revision と全文を必ず表示し、自動公開しない。
保留は画面を閉じるだけ。見送り理由の公開許可は既定 off として独立表示する。
全クライアントで stale revision は conflict として最新情報の再確認を要求する。

draft 保存と通知 outbox を同時 commit。通知は安定した draft ID で冪等化する。
通知から詳細へ遷移できる種別を追加し、終端状態・期限切れ時に解消する。
配送失敗は outbox に残し、再起動時に未配送と終端通知を照合する。
通知 acknowledge は公開承認にならない。公開履歴と生成履歴を同じ一覧で状態別に取得する。

既存 scheduler clock に接続した専用 tick は zone 付き cron の次の機会だけを扱う。
停止中の missed slots はまとめて再生しない。手動と定期の生成は同じ policy/claim を通す。
shutdown.started または context close で新 claim を止め、生成処理もキャンセルする。
時刻判定には注入した Clock を使う。Scheduler から transport へ接続しない。

## テスト・段階実装の検証計画

Phase 1 以降は短い Red/Green/Refactor と小さいコミットで進め、
各フェーズを最新 main から作成し、PR・CI・通常マージ・マージ後確認・後片付け後に次へ進む。
Phase 0 は本資料だけであり、製品コードの TDD 実施や新機能テスト成功とは扱わない。

| Phase | 追加する実装と失敗から始める主要テスト |
| --- | --- |
| 1 | migration、policy、状態・revision、永続 claim。二接続の同時予約、再起動上限、zone の日付境界、期限・停止・不正設定。 |
| 2 | public sources、専用 client、JSON decode。全対応 endpoint の送信 payload に private marker/tool/advisor がない、SKIP、未知 ID、注入、Unicode/byte 上限、mention 拒否。 |
| 3 | 全クライアントの一覧・詳細・編集・再生成・見送り。古い画面からの変更、編集後の全文表示、非公開 feedback、regeneration の回数・並行制御。公開経路は無効。 |
| 4 | 人間の証拠と transport。モデル/子/HTTP/コマンド/UI 自動化/fullauto からの偽装拒否。hash/DID/revision/期限の差し替え拒否。連打・同時承認・送信前後 crash・成功応答喪失・rkey 衝突・RecordNotFound・削除。実証済みの S1〜S4 対策が前提。 |
| 5 | outbox の保存前通知禁止、通知失敗と再起動、二重配送防止、終端通知解消、詳細への導線。 |
| 6 | fake Clock による cron/zone/日付/停止/長期休止。manual と scheduled の二接続競合、LLM 障害、同一 opportunity と再起動。 |
| 7 | 公開済み一致・類似 fixture、生成履歴、feedback 明示許可のみ取得。feedback で設定や権限が変更されない。 |
| 8 | backend `-Pfull`、terminal reactor、client の既存テストと build、静的解析、Windows/POSIX、CI と全クライアント統合を実施し利用手順と限界を記録。 |

外部通信はテストサーバー/モックのみ。実アカウントへ公開しない。
S1〜S4 のテストは Java サービスのモックだけでは足りず、子プロセスと
別クライアント・UI 操作を含め、ダミーの資格情報と隔離した fixture で検証する。
鍵を隠しただけ、tool を登録しないだけのテストで安全性を宣言しない。

## 将来の接続箇所

policy の入力 context に将来の読み取り専用内部状態 snapshot を追加できる。
同じ snapshot のうち公開利用を許可した表現情報だけを generator に渡せる。
snapshot は上限・公開情報許可・承認・transport の判断入力にしない。
将来のイベントは新しい trigger と opportunity ID で同じ検討 service を呼ぶ。
Bluesky 以外の行動追加時は、その内容モデルと policy/generator/UI/transport を追加する。
今は汎用 action framework、気分値、バイオリズム式、性格更新を導入しない。

## 公式仕様（2026-11-28 確認）

- [createRecord Lexicon](https://github.com/bluesky-social/atproto/blob/main/lexicons/com/atproto/repo/createRecord.json): rkey 入力と URI/CID 出力。
- [getRecord Lexicon](https://github.com/bluesky-social/atproto/blob/main/lexicons/com/atproto/repo/getRecord.json): repo/collection/rkey による照合、value と RecordNotFound。
- [feed.post Lexicon](https://github.com/bluesky-social/atproto/blob/main/packages/bsky/lexicons/app/bsky/feed/post.json): tid、text/createdAt、300 graphemes・maxLength 3000。
- [Record Key](https://atproto.com/specs/record-key): collection ごとの key 種別と識別範囲。
- [Lexicon string](https://atproto.com/specs/lexicon#string): maxLength は UTF-8 bytes。実装時にも再確認する。

古い `atproto/lexicons/app/bsky/feed/post.json` は調査時点で 404 だった。
現行の `packages/bsky/lexicons` の公式定義を参照した。
