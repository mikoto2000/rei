# Phase 1 / 2 validation

検証環境: Windows、Rust 1.91.1 / Node 24.11.1、2026-09-16。

| 検証                                        | 結果                                                                       |
| ------------------------------------------- | -------------------------------------------------------------------------- |
| Rust core tests                             | 41 tests passing                                                           |
| React / TypeScript tests                    | 12 tests passing                                                           |
| Playwright Desktop / Mobile                 | 4 tests passing（Chrome、1280×900 / 390×844）                              |
| TypeScript / ESLint / Prettier              | 成功                                                                       |
| cargo fmt / clippy                          | 成功                                                                       |
| Tauri desktop check                         | 成功                                                                       |
| Tauri desktop debug build (assets embedded) | 成功、`target/debug/rei-client.exe`                                        |
| npm audit                                   | 0 vulnerabilities                                                          |
| Android init                                | NDK not found                                                              |
| Android aarch64 cargo check                 | NDK clang / `aarch64-linux-android-clang` 不在で ring build 停止           |
| iOS                                         | Windows のため build 未実施。共通 Rust entry / Tauri config / icons を用意 |

Phase 1 の HTTP / SSE / replay tests と Phase 2 の Conversation / notification / UI tests を含む全 suite を実施しました。skip / ignore したテストはありません。

途中で確認した制約:

- Stronghold の libsodium ダウンロードで DNS 解決が失敗。CredentialStore の実装を RustCrypto の encrypted vault に切替。
- Windows Cargo incremental cache の rename が sandbox 内で拒否される警告。最終確認は `CARGO_INCREMENTAL=0` で実施。
- lib / bin の PDB 名衝突は lib を `rei_client_lib` へ変更して解消。
- 最初の Playwright 実行は4テスト成功後に sandbox 内の子プロセス終了で止まったため終了し、ローカル実行権限で再実行。4件成功・exit 0 を確認。
- Playwright が出す NO_COLOR / FORCE_COLOR の警告はテスト環境の表示設定。アプリの警告ではありません。
- 途中の誤った manifest 相対パスでの実行は未検証として扱い、正しいパスで再実行。

既存 Java サーバーには変更を加えていません。今回の検証は新設 client のテスト対象です。実サーバーへの接続と実機 OS 通知は未検証です。

実装ブランチ: `feature/tauri-rei-client`。

実装コミット:

- `968ecb4` feat: add rei profiles secure vault and HTTP client
- `8910418` feat: add run projections SSE reconnect replay and cancellation
- `663a1cb` feat: add persistent conversations session locking and application services
- `a190c99` feat: add Tauri conversation UI active runs and notifications

最後に Development / Security / Architecture / SSE / Mobile / TDD / 検証記録のドキュメントコミットを追加しています。
