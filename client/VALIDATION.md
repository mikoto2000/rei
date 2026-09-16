# Session History validation

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
