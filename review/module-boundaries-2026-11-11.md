# モジュール境界のコードレビュー（2026-11-11）

対象: `origin/main` の `3146df5f`。Backend のパッケージ依存、横断配線、実行制御とShell音声、terminalのMaven境界、Native Clientの構成を確認した。既存作業フォルダーの未コミット変更は対象に含めない。

## 修正した指摘

| 優先度 | 指摘 | 対応 |
| --- | --- | --- |
| P2 | `BackgroundCommands` がShell固有の `ChatResponseNarrator` に依存しており、実行制御をUIから独立させられない。既存B予算の唯一の実行→UI依存でもある。 | 実行側に単一操作の `CompletedRunNarrator` 出力portを置き、Shell実装がそれを実装する。オプション注入、呼び出し側での非同期実行、ExecutionScope、Webでの読み上げ抑止を維持。 |
| P2 | `core.chat.AgentRunConfiguration` がSession/Run/Project/Shell音声/voiceを組み立てており、実行機能と全体の配線が同じ境界に混在する。 | `bootstrap.AgentRunConfiguration` へ移動。Bean名・引数・設定条件・遅延ObjectProvider・executor終了処理は維持し、処理本体からのbootstrap依存を禁止。 |
| P2 | バックグラウンド処理が循環する例外原因を受けると `status` の走査が終了せず、失敗通知や実行の解放に到達できない。 | identity集合で訪問済みの原因を除外。循環例外の回帰テストを追加。 |

## 粒度と依存の評価

- Backendは単一Maven成果物だが、機能別packageと `application` / `core` 内の複数責務で構成される。全体を直ちにMaven moduleへ分けても、既存の相互参照を別artifactに移すだけになる。まず配線と呼び出し契約を分ける今回の変更は既存の移行計画Phase 1の最初の項目に対応する。
- 出力portは読み上げ1操作に限定する。Shell固有の同期通知フラグ、Markdown整形、音声サービスをportへ持ち込まない。音声出力の所有者と非同期実行は従来どおり呼び出し側が管理する。
- `terminal` は `contract` → `completion` → `cli` → `launcher` の責務を分けている。ただしcontract/completionはBackendのソースを選択再コンパイルしている。既存Phase 3にある共有成果物化は引き続き必要な設計課題で、今回の修正で完了したものではない。
- `core.Tools` の多機能facade、Run/Session/conversationの循環、その他Configurationの横断配線も残る。既存Phase 2/4/5の移行対象であり、公開Tool schema・Run所有権・取消・永続化の互換性確認を伴う段階的な変更が必要。今回、巨大SCC全体の解消は主張しない。
- Native Clientはentities/feature/UI/Tauri呼び出しを分離している。`App.tsx` は接続・選択・画面遷移の組立を担う。今回のBackend出力port変更はHTTP/SSE/Native Clientの契約を変更しない。

## 依存予算の更新方針

- A: application→UI禁止の予算は変更しない。
- B: BackgroundCommands→ChatResponseNarratorを削除し、実行→UI負債をゼロにする。
- C: 配線循環は旧AgentRunConfiguration由来の12クラス対を削除。処理本体の循環はBackgroundCommands→Shell実装の1辺をShell実装→実行portの1辺に置換する。広域SCC内に両機能が残るため、処理本体の循環辺数は減らない。bootstrapへの本番逆依存はゼロ。
- D: 旧設定由来の12クラス対をbootstrapへ対応付け、同一境界だったcore.chat型への7クラス対を明示。Beanの振る舞いは変更しない。
- E: Shell実装→実行portの1対、bootstrap→core.chatの7対とProjectServiceの1対を追加。検出した差分がこの明示的な対応だけであることを確認して更新した。

## 検証

検証完了後に実行結果を記録する。
