# 通常チャットの接続先切り替え・ChatGPT 利用枠: 実装記録

調査日: 2026-11-25。Phase 0 の基準は main
`42c40ea8f6d52d08cf3019c2915a567067b35c21`。
この文書は既存実装の棚卸しと技術検証を記録する。名前付き接続先や通常チャットの
ChatGPT 利用枠が利用可能になったという意味ではない。実アカウントへの要求は実行していない。

## 正本と参照資料

2026-11-26: 全体方針を [共通 LLM 接続基盤](llm-connection-foundation-design.md)として整理した。
OpenAI API、ChatGPT Plan、local の責務を同じプロファイルで表し、Provider、API、認証、
Capability、Replay を分離する。以下の Phase 0 の記録は調査時点の状態を表す。
Phase 1 は作業中であり、通常チャットへの接続先選択はまだ提供していない。

コードを正本として、以下を照合した。

- [既存のサブエージェント](chatgpt-plan-subagents.md)
- [Responses 設計](responses-api-design.md)、[運用](responses-api-operations.md)、
  [本番利用準備](responses-api-production-readiness.md)
- [接続先切り替え設計案](chat-endpoint-switching-design.md)
- [動的ツール選択](tool-discovery-design.md)
- [開発・品質ゲート](../DEVELOP.md)

`docs` と `.kiro` を探索した範囲では、通常チャットのサブスク専用設計書は独立した
ファイルとして見つからなかった。本依頼の仕様と本記録を追加の入力として扱う。

## 既存実装の棚卸し

| 対象 | 現在の実装と根拠 | 今回の対応 |
| --- | --- | --- |
| Chat Completions / Responses | `LlmApiType`、`LlmProtocolAdapter`、`ChatCompletionsAdapter`、`ResponsesAdapter` が存在 | 再利用。新しいエージェントループを作らない |
| 本番 Responses | `ResponsesProductionPolicy` は `chat` / `output-limit-planner`、OpenAI URL、明示モデル、出力2048、encrypted、36 tools、low effort、120秒などの構成を検証 | サブスクへ流用不可。特に `max_output_tokens` はサブスクで送信禁止 |
| 接続設定 | `LlmProperties.features` は `Map<String, Server>`。各機能に単一の接続設定 | 名前付きカタログ、provider、account-ref、active が必要 |
| 不変値 | `ResolvedLlmEndpoint` は API、URL、モデル、generation、capability、credentialRef、timeout 等を保持。秘密値を含めない | 再利用。ただし provider とアカウント登録の同一性が不足 |
| キャッシュ | `LlmModelProvider` のモデル・構成、`LlmChatClientProvider` のクライアントは機能名／並列モードがキー | 選択スナップショットを含む識別へ変更が必要 |
| モデル選択 | `ChatModelResolver` と選択モデルを用いた options 解決が存在 | 接続先ごとのモデルへ整理する必要 |
| Capability | `LlmCapabilities` は reasoning replay、最大ツール数、対応設定の集合 | model と認証経路の実効制約、未確認状態などが不足 |
| Run / 再開 | Responses の既存テストは実効設定、owner、承認、Checkpoint、別プロセス再開を扱う | 保存・再送基盤を再利用。動的選択を受付時に固定する統合は未実装 |
| reasoning | `ResponsesReasoningReplay`、modelmessage パッケージに保存・所有者照合・再送基盤 | 再利用。異なる接続先の opaque payload は共有しない |
| 動的ツール | `ToolCatalog`、`RunToolAvailability`、`ToolRequestAssembler`、`ToolRequestValidator`、`searchTools` | 基本・専門分類、LRU、公開上限、要求スナップショットを維持 |
| namespace | 同梱 SDK は `NamespaceTool` と `ResponseFunctionToolCall.namespace` を持つ。Spring AI の `ResponsesItemMapper.toolCallPart` は namespace を保存しない | 型付き SDK 境界での変換・完全な照合が必要。既存の flat 経路は namespace を拒否する |
| 認証 | `PlanOAuth`、`ProtectedCredentialStore`、DPAPI、host ID、scope 検証、アカウント別カタログが存在 | 再利用。OAuth 基盤を別に作らない |
| Refresh / logout | credential directory の JVM lock と OS lock 内で読取・refresh・atomic replace・logout を処理 | Single Flight の既存実装を維持。現状はアカウント別より広い排他 |
| 登録同一性 | `TokenSet` は clientId / subject を保存。公式上 issued client ID は user / Workspace に結び付く。Run に固定する登録参照と再登録の識別はない | 既存の登録識別情報を使った Run の照合が必要。label / email だけで継続せず、Workspace フィールドの新設自体を必須とはしない |
| Plan 推論 | `PlanClient` は要求フィールドの明示生成、公式固定 URL、手動履歴、SSE、上限、取消。`Input` は user / assistant テキストだけ | OAuth / カタログは再利用。通常チャットのツール・画像・ファイル経路にはそのまま使えない |
| app-server | `PlanAppServerClient`、既存の委譲経路が存在 | 既存用途を維持。通常チャットを委譲しない |
| 外部送信 | `ChatGptCommand` の単発推論は `ToolPermissionGuard` を呼ぶ。既存委譲は内容 digest に承認を結び付ける | 推論送信の許可をツール権限と分離する必要。CONVERSATION のツール禁止を緩和しない |
| 補助推論 | `LlmModelProvider` の音声補正などに chat 構成への参照が残る | 用途別解決へ整理し、active の暗黙継承を防ぐ |
| CLI / GUI | `/llm capture`、`/model`、`/chatgpt` は既存。接続先選択サービスと `/llm chat` はない | 共通選択サービスから CLI、terminal、GUI を接続する必要 |
| 利用枠 / 同時実行 | 既存のエラー分類・Run 予算はあるが、通常 chat と Plan 委譲を結ぶ要求単位の制御はない | アカウント登録と制限の範囲を区別して統合する必要 |

`ResolvedLlmEndpoint.fingerprint()` は再送互換性の識別であり、実効設定全体の同一性や
認証主体を保証しない。現在は出力上限・temperature・timeout・credentialRef を含めない。
Run 固定や復旧では fingerprint だけを照合せず、実効スナップショットと固定した登録参照を
確認する。資格情報そのものを fingerprint / Run / Checkpoint へ追加して解決しない。

## 公式仕様の照合

2026-11-25 に次の公式ページを確認した。プレビュー仕様は実装時にも再確認する。

- [Overview](https://developers.openai.com/siwc/token-sharing-open-source):
  OSS / ローカル提供の利用枠権限と、永続 host ID、user / workspace に結び付く登録の境界。
- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference):
  選択アカウントの `/v1/models` の表示対象 slug、Bearer と公開 `/v1/responses`、
  正常終端イベントによる完了判定。
- [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations):
  HTTP は store false / stream true、input 配列、instructions または developer、履歴を明示再送。
  system message、previous_response_id、max_output_tokens、temperature、top_p、metadata 等を送らない。
  function/custom は namespace 等に格納。hosted MCP / tool_search 等は非対応。
  text / image / file はモデル依存。Files upload と audio/video は非対応。
- [Errors and recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery):
  granted scope、direct admission と Responses error の区別、明示的な認証復旧。
  利用枠エラーを全プラン枯渇や reset 時刻へ一般化せず、暗黙的に課金経路を変えない。
- [Function calling](https://developers.openai.com/api/docs/guides/function-calling):
  namespace の定義、tool call/result の往復、reasoning item の維持。
  hosted tool search を既存 searchTools の代わりにしない。

## Phase 0 の技術検証

Maven の dependency tree で Spring AI **2.1.0** → OpenAI Java SDK **4.101.0** を確認した。
同梱 source JAR と公開型を確認し、まず既存 SDK の範囲で補完できると判断した。
依存更新や JSON body の HTTP interceptor 書換えは行っていない。

### 再現した namespace 欠落

`ResponsesTransportContractTest.flatToolRequestsRejectNamespacedCallsBeforeSpringCanDiscardTheNamespace`
は HTTP 応答に未登録 namespace と既存と同じ tool 名を設定する。
blocking / streaming の両方で、修正前は例外が出ず **2件の assertion failure** となった。
コンパイル失敗は Red として数えていない。

原因は Spring AI の ToolCallPart 変換で namespace が失われること。
既存の flat 要求ではどの namespace も公開していないため、`ResponsesSdkBoundary` で
SDK の型付き function call を確認し、変換前に拒否する。SSE の added / done / terminal
と blocking 応答を同じ検証へ通す。名前の接頭辞除去や推測解決は行わない。

### SDK の表現能力

`ChatGptPlanSdkFeasibilityTest` は loopback HTTP server に対して同梱 SDK の
型付き `ResponseCreateParams` / `NamespaceTool` を送信する。
実際の HTTP JSON のトップレベル項目を許可リストと厳密比較し、Bearer、input 配列、
namespace `rei` の `searchTools`、store false、stream true を確認する。
これは SDK の表現能力の確認であり、通常 chat への統合や実サービス受入ではない。

SSE 終端、失敗、usage 不明、上限、取消、reasoning 再送、画像、動的ツール、
承認と別プロセス再開は既存の Responses / Plan fixture テストを再利用して検証する。
namespace を維持した tool call/result の統合、ファイル入力、通常 chat の外部送信許可、
アカウント再登録・Workspace・要求単位の共有制御は後続の実装と試験が必要。

### ローカル検証記録

| 検証 | 結果 |
| --- | --- |
| Red: 未登録 namespace、blocking / streaming | 2件とも「例外が出ない」という意図した assertion failure。`2d06299d5` |
| Green: SDK 境界の最小修正 | `068c041f4`。通信契約134件 + SDK 表現能力1件、failure / error / skip 0 |
| Refactoring / SDK fixture | `fe033981c`。全回帰内でも上記135件と再送契約169件が成功 |
| Backend `-Pfull,quality verify` の全テスト | 8,360件、成功8,348件、期待 skip12件、failure / error 0 |
| 期待スキップ照合 | `config/test-expected-skips.json` と件数・suite・method・理由が完全一致 |
| 上記 verify の終了状態 | テスト後の Spotless で失敗。テスト2ファイルの書式を指摘。verify 全体を成功とは記録しない |
| 書式修正後の `-Pquality -DskipTests verify` | 成功。main / test compile、JAR、Spotless 3,187ファイル、Checkstyle違反0、SpotBugs指摘0・解析エラー0。全回帰の再実行は省略 |
| 軽量 CLI reactor | 32 suite、194件、failure / error / skip 0 |
| 実アカウント | 未実施。実サービス適合性は未確認 |

書式修正は今回の Java 3ファイルに限定した。既存テストの削除、期待値変更、
skip 追加、解析抑制、アーキテクチャ制約の緩和は行っていない。
PR の最新 head、必須 CI、レビュー、merge SHA、マージ後 CI と後片付けは GitHub と
worktree 外の作業記録で最終照合する。

## 設計資料との不一致・競合

- `DEVELOP.md` の「Responses 本番利用は無効」は現行の限定的な本番 policy より古い。
- Responses の進捗書と本番準備書には過去の固定モデル・profile の記録が残る。
  現行 `ResponsesProductionPolicy` はモデル名を旧固定 slug だけに限定していない。
- 動的切り替え設計案の「実行中のフォールバック維持」は今回の暗黙フォールバック禁止と
  併せて整理が必要。新しい名前付き経路で他課金先への自動退避を認めない。
- 同案のサブエージェントへの親設定継承案は採用しない。補助用途を独立して解決する。
- 既存4 worktree は dependency scan / Java CI benchmark の別作業。変更・削除しない。
  `.github/workflows` と品質ゲートを今回のために緩和しない。
- Responses、modelmessage、Run の変更は既存抽象化へ統合する。
  本調査時点で GitHub の open PR 検索結果は0件だったが、各 Phase 開始時に再取得する。

## Phase と公開の条件

### Phase 1 の共通基盤（作業中）

`ChatEndpointCatalog` は名前付き接続先を起動時に検証・固定し、公開メタデータから
URL / API key を分離する。`LlmProperties.Server` は既存 flat 設定と併存する。
Endpoint ID、モデル、Provider、API、認証方式、account-ref、generation、利用経路、
実効 Capability を同じカタログで表す。設定検証はネットワーク要求を発行しない。

Provider の `openai-api` / `openai-compatible` / `chatgpt-plan` と、認証の
`none` / `api-key` / `chatgpt-oauth` は別に検証する。名前付き接続先にキーの指定も
認証の指定もない場合は `unconfigured` とし、旧 flat の認証解決は `legacy` として隔離する。
これら二つは内部状態であり、利用者が設定する認証方式ではない。
未認証 Plan の登録だけでは起動を妨げない。

Capability は Rei adapter の制約、`server-capabilities`、`model-capabilities` の交差とする。
新規接続先のサーバーは未宣言なら UNKNOWN、モデルの reasoning 再送も未宣言なら UNKNOWN。
モデル名や URL、API 選択、Replay 選択から、サーバーの互換性を確認済みとは判断しない。
この宣言を実サーバーでの試験結果として報告しない。
既存 `LlmCapabilities` の record 構成は変更せず、過去の再送 fingerprint との互換性を維持する。

主な短い TDD サイクルは以下のとおり。コンパイル失敗を Red に数えていない。

| 対象 | Red | Green |
| --- | --- | --- |
| 名前付き登録と旧設定 | `cf42fa699`、名前付き登録の assertion failure | `8c3328dcb`、8件成功 |
| 不正設定の安全な診断 | `b5ddaf7a9`、16件の assertion failure | `46803b7e3`、24件成功 |
| Provider / API / Plan の境界 | `776b00378` | `543892780`、37件成功 |
| 不変コピー、補助用途、YAML merge | `ca8f45dfa`、9件の assertion failure | `aa346b0ca`、46件成功 |
| 能力と利用経路 | `1716e1cbf`、13件の assertion failure | `588ea1654`、既存モデル解決・fingerprint を含む82件成功 |
| 独立した認証とサーバー契約 | `c26567153`、18件の実行時 failure / error | `da6d21a48`、100件成功 |
| サーバー / モデルの未確認状態 | `c219c8a8b`、8件の assertion failure | `ae991627e`、110件成功 |
| 静的経路で認証指定を無視しない | `f99f2caa4`、7件の assertion failure | `019362664`、関連608件と品質ゲート成功 |

Phase 1 の関連回帰608件は failure / error / skip 0。Responses の再送169件と通信134件、
アーキテクチャ検査、実際の Spring アプリ起動を含む。Spotless 3,198ファイルが clean、
Checkstyle 違反0、SpotBugs 指摘0・解析エラー0。全回帰と最新 head の CI は別に確認する。

認証方式が安全な表示値 `api-key` を持つようになったため、旧 JSON メタデータ試験の
「文字列 api-key が一切ない」という検証は、秘密値の canary と JSON フィールド名
`apiKey` / `api-key` が存在しない検証へ整理した。キー値の非公開要件は維持する。
サーバー能力の追加時には、前の新規テストの既定値を UNKNOWN に更新し、対応済みを
検証する fixture は明示的なサーバー宣言を追加した。

これは構成・検証の基盤であり、通常チャットへの新規ルーティング、`/llm chat`、
Run / Checkpoint 統合、認証なしの実 HTTP、Plan の namespace 往復は後続 Phase の対象。
これらの実装や実サービス試験が完了したとは扱わない。

| Phase | 状態 | 完了に必要な証拠 |
| --- | --- | --- |
| 0 | [PR #640](https://github.com/mikoto2000/rei/pull/640) を通常 merge。merge SHA `7afd50192bca1b5b4cf3eeb77ab02e2accf55fe7` の CI 成功・後片付け済み | 追加要件の policy 分類は共通基盤設計に反映。local の実互換試験は未実施 |
| 1 | 共通プロファイルの構成・検証を TDD で作業中 | 認証 / server / model 能力の分離、最新変更の回帰・品質ゲート、PR と CI / merge / 後片付け |
| 2–3 | 未着手 | Run 固定、CLI と model 統合 |
| 4–8 | 未着手 | 認証参照、namespace の完全な往復、権限分離、復旧、履歴・機能 |
| 9 | 未着手 | 共通サービスを使った GUI とキーボード操作 |
| 10 | 未着手 | 全回帰・解析・全 Phase のマージ実績と利用者向け手順 |

実装されていない `/llm chat` の利用手順や、サブスクを有効化する設定例は公開しない。
実接続試験を行う場合は、所有者がログインし、利用アカウント・モデル・送信対象・利用枠消費を
明示的に許可した専用環境で実施する。fixture 合格だけで実接続済みと報告しない。

### 既存認証・単発経路の手動確認

実接続を明示的に許可した場合だけ、専用 Project / Session と既存の承認手順を使う。
保護対象のファイルや会話を含めず、利用枠を消費することを確認する。

```text
/chatgpt login --account personal
/chatgpt status
/chatgpt models --account personal
/chatgpt infer --account personal --model <表示されたslug> "Say exactly: Hello, world!"
```

期待結果はブラウザで本人が認証・同意し、認証状態とアカウント固有のモデル一覧を取得でき、
明示的な推論承認の後に正常終端した応答を受け取れること。scope 不足・利用枠超過・失効時は
理由を表示し、他の課金先へ退避しないことを確認する。
この確認は既存の単発経路に限る。通常チャットの namespace、ツール権限、動的切り替え、
履歴継続の実サービス受入を代替しない。本作業では未実施。
