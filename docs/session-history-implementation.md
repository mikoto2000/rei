# Session History 実装報告

ブランチ: `feature/session-history-api`

## コミット

- `c072218 feat: persist session metadata and add cursor query service`
- `50ec8cd feat: expose authenticated session and durable turn history APIs`
- `4a2c195 feat: share session history queries with shell and verify restart`
- 文書・検証結果は `docs: document session history contracts and verification` にまとめる。

## 調査した既存構造

SessionRegistry は30分で消える in-memory 存在判定、ChatSubmitService は project 検証・ID採番・QUEUED 登録・router dispatch を担当していた。ConversationTurnStore は runId/request/status の永続 lifecycle、ConversationLogStore は conversationId/speaker/timestamp/content の JSONL、ChatMemory はモデル用の bounded context だった。

ConversationIds と ProjectContext は `project:<projectId>:chat:<id>` を構成する。turnId は runId と同一。既存 Shell は picocli HistoryCommand から HistoryShellService の show/list/search を呼び、slash parser がコマンドとして振り分ける。Web は専用 record DTO、ApiExceptionHandler の400/404/409、SecurityConfig の Bearer filter を持つ。Clock bean と atomic JSON の ProjectRegistry が既にあった。

## 主要変更

| 分類 | クラス |
| --- | --- |
| metadata・port | SessionMetadata、SessionRepository、FileSessionRepository |
| 共通 application service | SessionQueryService、SessionTurn、ConversationHistory |
| policy | SessionTitle、CursorCodec、CursorKey、Pagination、HistoryPage |
| 書込み | ChatSubmitService、ConversationTurnStore、ChatExecutionService |
| runtime | SessionRegistry、RunRegistry |
| Web | SessionController、SessionResponse、SessionListResponse、SessionTurnResponse、SessionTurnListResponse |
| wiring・Shell | SessionHistoryConfiguration、WebApiConfiguration、HistoryCommand |

metadata は `<rei-data-dir>/sessions.json` を atomic replace して保存する。Repository は monitor で create/touch/list/read を保護し、受理を永続化してから runtime と queue を登録する。同期 enqueue 失敗は metadata を復元し runtime 登録を除去する。SessionRegistry は cache に限定し、再起動・cache purge 後も Repository から継続する。

title は `String.codePointCount` と `offsetByCodePoints` で先頭80 code point を切り出す。省略記号は追加しない。初回 title／projectId／createdAt を保持し、継続は Clock の updatedAt のみを単調更新する。

cursor は version・resource/filter scope・Instant 秒/nano・ID の URL-safe Base64。Session は updatedAt DESC＋sessionId ASC、Turn は createdAt ASC＋runId ASC の keyset を使い、limit+1 の lookahead で nextCursor を返す。

## API と Shell

- `GET /api/v1/sessions`: `{items, nextCursor}`。projectId 絞込み可能、未知 project は空一覧。
- `GET /api/v1/sessions/{sessionId}`: metadata の専用 DTO。未知 Session は404。
- `GET /api/v1/sessions/{sessionId}/turns`: `{sessionId, items, nextCursor}`。永続 Turn の日時・user・assistant・turnId=runId を返す。
- 両一覧は既定50／最大100件。不正 limit/cursor は400。3 API とも Bearer 認証なし・不正キーは401。
- `/history`: 共通 SessionQueryService の最初のページ。full ID、project、title、updatedAt、次ページコマンドを表示する。
- `/history show <sessionId>`: metadata と時系列 Turn。`--limit`／`--cursor` に対応する。
- 旧 list/search、show 単体、`--project`／`--last`／`--all` は維持する。削除・改名は追加しない。

## 移行と制約

backfill は行わない。旧 Turn には時刻・応答がなく、旧 JSONL には runId がないため、受理時刻とメッセージ対応を確実に復元できない。旧ログは既存コマンドで参照する。日時のない旧 Turn は新 cursor API から除外するが、lifecycle 読込は維持する。

新一覧は導入後に Web ChatSubmitService が受理した Session が対象。ファイル adapter は単一プロセス writer と全ファイル読込を前提とする。Shell／Web に返す結果はページ単位。ページ間で更新された Session はカーソルより前へ移動する場合があり、snapshot 保証はない。queue の crash recovery は追加していない。Turn は最初の入力と最終応答を表示し、SSE の中間 delta／tool／介入メッセージの全ログは含めない。

詳しい保存・互換性・pagination 契約は [Session History 仕様](session-history.md) を参照。

## 検証

- 追加35件（新規8テストクラス33件＋既存クラスへの新規2件）。既存の関連テストも依存注入・新仕様に合わせて更新。
- Red → Green: タイトル、永続化、Chat 受理、共通 query／cursor、HTTP、Turn 記録、Shell の未実装・未公開状態から実装。
- 実 HTTP の作成→再起動→一覧／詳細／Turn→同一 Session 継続、固定 project、同時刻 pagination、途中 insert、並行 metadata 更新、保存／enqueue 失敗、認証を検証。
- 全体: **1,803件、失敗0、エラー0、スキップ2、BUILD SUCCESS**。追加テストにスキップはない。既存 ExternalAgentPolicyTest の2件は Windows のシンボリックリンク作成権限に依存する。

実行コマンド:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=F:\project\rei\.m2\repository' '-Drei.data-dir=F:\project\rei\target\session-history-test-data' test
```

初回の制限環境では既存ログ保存先／sqlite-vec のダウンロードで34エラーだったため、データを作業フォルダー内へ隔離して必要なネットワーク権限で再実行し成功した。テストログは `target/session-history-full-test.log`。

`package` は既存 target の JAR リネームで失敗したため、製品 POM と同一内容で build directory だけ `target/session-history-package` に変更した一時 POM を使用し、`-DskipTests package` に成功した。Spring Boot の repackage を含む。検証用 POM は削除済み。ログは `target/session-history-package.log`。

`git diff --check` は成功。専用 formatter／static-analysis plugin は既存 POM に設定されていない。README、usage、Web requirements/design/task を更新した。
