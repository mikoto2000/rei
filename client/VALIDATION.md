# Live AgentEvent Activity validation

## ユーザー／れいのアイコン（2026-09-22）

Settings の「ユーザーアイコン」で PNG / JPEG / WebP（2 MB 以下）を選択・リセットできます。
画像は端末内の WebView localStorage に保存し、サーバーには送信しません。
過去の会話と実行中の会話に共通で反映します。
れいの画像は `public/rei-avatar.png` に同梱し、会話・サイドバー・空の会話画面で使用します。

Frontend 41件、build（型検査含む）、lint が成功。
Playwright の Desktop / Mobile で画像選択、壊れた画像の拒否、再読込後の復元、
保存済み／実行中の会話への表示、リセットを検証しました。
全体検証ではページ読み込みの30秒タイムアウトが発生したため、複数回の
再読み込みを行うアイコンテストの制限を60秒に設定しています。
Tauri 実機でのファイル選択とネイティブ実行ファイルの再ビルドは未実施です。

## テキスト中心のイベント表示（追加検証）

`feature/native-plain-event-text`: イベントの枠・太字・段組みを取り除き、12px 等幅フォントのテキスト行に変更。Frontend 32件、Desktop／Mobile 10件、型・lint・format、Windows assets 埋込みビルドが成功しました。Rust／Server の変更はありません。

## 出力順のチャット表示（追加検証）

`feature/native-interleaved-events` で本文とイベントを出力順に混在表示するよう変更しました。Rust 60件、Frontend 31件、Desktop／Mobile Chrome 10件が成功。型・lint・format・clippy と Windows Tauri assets 埋込みビルドも成功しています。サーバーの変更はなく、Java 全体テストはこの追加変更では再実行していません。

開始／完了のイベントは届いた位置に残ります。連続した同一 Message の delta だけを結合し、完了本文の重複、再接続時の重複を防ぎます。過去 Session 履歴と永続化の方針は同じです。

## 初回ライブ Activity 実装の検証

2026-09-17、ブランチ `feature/native-live-agent-events`。Windows / JDK 25 / Rust 1.91.1 / Node 24.11.1。

| 検証                                                           | 結果                                                                         |
| -------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| Server 全体（unit / integration / property / Shell 回帰）      | 1,816件中1,814件成功、2件既存スキップ、failure/error 0                       |
| Rust core / HTTP / SSE / application                           | 58件成功（全体57件の成功後、Projection回帰1件を追加して15件再検証）          |
| Frontend Vitest                                                | 29件成功                                                                     |
| Playwright Chrome Desktop / Mobile                             | 8件成功（1280×900 / 390×844）                                                |
| TypeScript / ESLint / Prettier                                 | 成功                                                                         |
| cargo fmt / clippy --all-targets --features native -D warnings | 成功                                                                         |
| Tauri Windows assets 埋込み debug build                        | 成功。`../target/native-live-desktop/debug/rei-client.exe`                   |
| Android aarch64 cross-check                                    | NDK の `clang.exe` / `aarch64-linux-android-clang` 未導入で ring build 停止  |
| iOS / 実機 Mobile                                              | Windows 環境のため未検証                                                     |
| 実サーバー / 外部 LLM を用いた手動操作                         | 未実施。結合テストはローカル mock HTTP/SSE、UI は command/event 境界 fixture |

通常の desktop 出力先は既存の Rei Client が起動中でロックされていたため、`CARGO_TARGET_DIR=F:\project\rei\target\native-live-desktop` を設定してビルドしました。既存プロセスは終了していません。

Server の実行:

```powershell
$env:JAVA_HOME='C:\Java\jdk-25'
$env:REI_DATA_DIR='F:\project\rei\target\native-live-test-data'
.\mvnw.cmd -o -q '-Dmaven.repo.local=F:\project\rei\.m2\repository' test
```

sqlite-vec の依存取得とテスト用のファイル操作が可能な権限で実行しました。初回は sandbox による依存取得・ログ保存先の制限でエラーになり、テスト保存先を分離して再実行しました。全体検証で発見した既存テストの不安定要因も修正しました。

- ToolsTest: stdout と stderr の独立した読取を両方待つ。短いプロセスの auto 完了テストは PowerShell 起動を含むため待機上限を10秒にする。
- MemoryServicePropertyTest:生成された `OR` 等を FTS 演算子と解釈させず、検索対象のリテラルトークンとして引用する。
- 関連する production の Tool / Memory 実装は変更していません。
- スキップ2件は既存 `ExternalAgentPolicyTest`。

詳細な [イベント対応表・公開 schema・TDD記録](../docs/native-live-agent-events.md) を参照してください。過去イベントの永続化、SessionTurn の trace 拡張、ReplayBuffer retention の変更はありません。

## Previous Session History validation

検証環境: Windows / Rust 1.91.1 / Node 24.11.1、2026-09-17。
実装ブランチ: `feature/session-history-client`。既存 Session History API ブランチから作成し、Java サーバーは変更していません。

| 検証                                  | 結果                                                                    |
| ------------------------------------- | ----------------------------------------------------------------------- |
| Rust core / HTTP / application / SSE  | 51件成功（既存41件から10件追加）                                        |
| React / TypeScript                    | 27件成功（既存12件から15件追加）                                        |
| Playwright desktop / mobile           | 6件成功（既存4件から2件追加）                                           |
| TypeScript / ESLint / Prettier        | 成功                                                                    |
| cargo fmt / clippy native all-targets | 成功                                                                    |
| cargo check native                    | 成功                                                                    |
| Tauri desktop debug build             | 成功。assets 埋込み `src-tauri/target/debug/rei-client.exe`             |
| Android aarch64 check                 | 失敗。NDK の clang / aarch64-linux-android-clang 不在で ring build 停止 |
| iOS                                   | Windows のため build 未実施。共通 command / Rust / responsive UI を利用 |

コミット:

- `11e389b` feat: add server session history client and authoritative resume
- `1ccc77e` feat: add paged session history and resume UI
- `81e7960` test: cover final turn page and unauthorized session detail
- この検証記録・README・TDD の文書コミットを最後に追加。

## 設計と実装

既存は React → typed command → native DTO → Application / ConversationService → ReiClient という構造で、Conversation metadata を app.json に保存していました。RunManager、Projection、SSE parser/reconnect と credential vault は既存のものを使用します。

追加した主要 module は Rust の `domain/history.rs`、`api/history.rs`、`application/session_history.rs`、`dto.rs` と React の `features/history/` です。HTTP wire DTO、domain、UI DTO を分離し、domain に Deserialize / Serialize を持たせません。SessionHistoryService は ReiClient port に依存します。

一覧・詳細・Turn API は Rust で Bearer 認証を付与します。limit は1〜100、既定50。ID は path segment、cursor と project ID は query としてエンコードします。cursor は加工しません。本文や token を含む生エラーを UI に返さず、401、404、cursor/limit、通信失敗、500、不正レスポンスを application error に変換します。現行サーバーの400は詳細なエラーコードを返さないため、cursor付き400は InvalidCursor、cursorなし400は InvalidInput と扱います。limit は通信前に検証します。

Conversation の server title / project / timestamps / transcript は永続化しません。起動中の会話ハンドルと表示 cache のみ保持します。旧 app.json の conversations は読込み時に無視し、次の保存で除去します。再起動後は Session API から一覧を再取得するので別端末の会話も表示できます。

HistoryPager は items、nextCursor、loadingInitial、loadingMore、refreshing、error、stale を管理します。追加取得中の多重呼出しを防止し、server / project / session 切替後の遅延レスポンスを generation で無効化します。更新は cursor をリセット、取得失敗は既存表示を未同期として保持し、明示 retry で回復します。重複を sessionId / runId で排除し、警告には件数だけを記録します。

Conversation List は更新日時・title・project、All Projects filter、loading / empty / error / retry / more を表示します。選択時は Session detail を取得して project を固定し、Turn をサーバーの昇順のまま下へ追加します。null assistant は未記録として表示します。Desktop はサイドバーと詳細、Mobile は一覧と詳細を切替え、戻る操作と server selector を利用できます。

送信前に detail を再取得し、ConversationTarget::Existing の projectId / sessionId を使用します。404 / 409 では再送・自動 fork せず、409 は detail を再取得します。新規会話は sessionId を送らずサーバーで採番します。受理後は既存 RunManager へ登録して SSE 購読し、Tool Activity / Working Set / Stop / reconnect を継続します。送信受理時と terminal 時だけ履歴を更新し、message delta ごとに HTTP を呼びません。保存済み Turn とライブ Run を runId で同じ位置に統合します。

## 検証の範囲と制約

Rust Axum mock の結合テストでは一覧 → 詳細 → Turn → 同一 Session の POST → SSE completed と、再起動後の一覧再取得を確認しました。401 / malformed / opaque cursor / URL escaping / 404 / 409 / no retry / metadata非保存も検証しています。ブラウザテストは command/event 境界を置換しており、実 Tauri と Java Server を通した実機 E2E ではありません。

Playwright の最初の sandbox 内実行は6件成功後に子プロセス終了が停止したため中断し、通常実行権限で再実行して exit 0 を確認しました。最終変更後も6件成功・exit 0 を確認しています。スクリーンショットで desktop/mobile を確認し、横はみ出しチェックも通しています。

未検証: 実 Rei Server の API Key を用いた接続、Android APK / iOS build と実機、OS 通知。未対応: offline transcript、再起動前の active Run 自動復元、検索、削除API。履歴更新は取得済みページを先頭へ戻すため、続きは再度「次のメッセージ」を押して取得します。

サーバー更新日時はローカルでの会話選択や送信では上書きしません。Session API を使うには対応するサーバー実装が必要です。既存の server URL 変更・削除制約により、起動中に開いた会話がある場合はアプリ再起動後に設定を変更してください。

最終コミット後に `git status --short` が空であることを確認します。
