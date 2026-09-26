# 長期記憶 / Manual Sleep 実装報告

## 実装範囲

専用ブランチ `codex/long-term-memory` で実装。Manual Sleep、SQLite 永続化、Memory Consolidation、通常 Chat での Retrieval、memory コマンド、実会話に基づく LESSON / PROCEDURE 抽出を追加した。Auto Sleep の起動機構は追加していない。

利用・設定・DB の詳細は [long-term-memory.md](long-term-memory.md) を参照。

## 主要クラス

追加:

- モデル: `MemoryCandidate`, `LongTermMemory`, `MemorySource`, `MemoryAction`, `MemoryResolution`, `SleepRun`
- 永続化: `MemoryRepository`（既存 MemoryService が作成するテーブルを拡張）
- 抽出・検証: `MemoryCandidateExtractor`, `MemoryResolutionModel`, `LlmMemoryProcessor`, `MemoryOutput`
- 統合: `MemoryResolver`, `SleepService`
- 検索・Context: `MemorySearchTerms`, `MemoryRetriever`, `MemoryContextAdvisor`
- CLI: `SleepCommand`, `MemoryCommandSupport`, `MemoryCommand.ShowCommand`
- イベント: `MemoryEvents`, `MemoryLifecyclePayload`

変更:

- `MemoryType`, `MemoryScope`, `MemoryStatus`: 既存値を保持して新分類を追加
- `MemoryProperties`: retrieval / sleep の設定、既存コンストラクタ互換、部分設定時の既定値
- `MemoryCommand`, `RootCommand`, `ReiApplication`: 新コマンドと JLine 出力を接続
- `LlmChatClientProvider`: CHAT のみ MemoryContextAdvisor を登録
- `LlmModelProvider`: MEMORY モデルと fallback の ambient tools を拒否する専用 accessor
- `ContextAssembler`: hard limit 不足時に補助記憶を外し、既存履歴の保持を優先
- `SubAgentResultSchema`: 既存の schema 検証 API を公開して再利用
- `AgentEventFactory`, `AgentEventType`, `AgentEventPayload`, `WebApiEventMapper`: lifecycle と件数のイベント

## 既存設計との調整

| 既存設計 | 要件との差異 | 採用設計 | 理由 |
|---|---|---|---|
| 既存 memory-consolidation.db と memories / tags / sources / relations | 新 DB の候補が memory/memory.db | 同じ DataSource / DB を additive migration | DB・Repository を重複させず、既存保存データを維持 |
| 旧 Memory は期限ベース scope、Project ID / source Turn を持たない | GLOBAL / PROJECT と根拠追跡が必要 | enum を追加し同一テーブルの LongTermMemory ビューを追加 | 旧 API・既存テストの互換性を保持。根拠不明の旧行は自動注入しない |
| ConversationTurnStore は追記順で runId を持つが数値 sequence は持たない | 未処理 Turn の cursor が必要 | 原典リストの 1-based 位置を Sleep cursor、runId を source Turn ID として使用 | 既存履歴を書き換えず、timestamp の逆転や同時刻にも影響されない |
| Context Compression に独自 summary sequence がある | Sleep と同じ cursor に見える | Sleep cursor は別 DB の成功 Run のみに保存 | 圧縮と長期記憶を独立させる |
| 旧 Consolidator は自由形式の出力に fallback がある | schema failure は保存禁止 | 新 LlmMemoryProcessor + 共通 SubAgent parser/schema validator | 旧コマンド互換を維持し、新 Sleep は strict fail-closed |
| 旧 memory FTS は content のみ | 日本語・summary・tags の検索 | FTS5 trigram テーブルを追加、短語は escaped LIKE | 外部ベクトル DB なしで日本語の検索に対応 |
| list/search/forget に旧 API がある | Project 分離と ARCHIVED が必要 | Spring 実行時に MemoryCommandSupport へ委譲 | コマンド名の重複を避け、旧コンストラクタを維持 |
| RuntimeContextAdvisor がユーザー入力へ時刻を付加 | 検索クエリへ時刻が混入 | MemoryContextAdvisor の実行を先にする | 実 Chat 経路の統合テストで検出・修正 |
| ContextAssembler は固定補助情報を hard limit 計算へ含める | 記憶が追加停止要因になり得る | 圧縮後も不足すれば補助記憶を除外 | Context Compression の既存保証を保持 |

## 保存・競合・失敗時の判断

抽出と LLM 照合を DB トランザクション外で完了し、全変更と成功 Run を同時 commit する。処理位置は成功 Run の最大 toSequence であり、独立した可変 cursor を持たない。書き込み途中の例外・キャンセルでは rollback する。

同一 Session の同時実行は拒否する。commit 前に cursor と対象記憶の snapshot を検証し、別 Session の更新があれば retry を要求する。完全一致は検索候補数とは独立に取得し、ACTIVE の scope / Project / 正規化本文の一意制約で同時 INSERT の重複も防ぐ。

CONFLICT は relation に保存し、双方の ACTIVE を維持する。未解決の対立情報を通常 Chat に無条件注入せず、show / search / Sleep Report で確認できるようにした。外部イベントへ本文を流さず、所有情報と件数だけを公開する。イベント配送の失敗が DB commit の成否を変更しない。

## TDD・検証

以下の順で Red → Green → Refactor を実施した。

1. モデル、永続化、Project 分離、出典と transaction rollback の未実装テストを追加して失敗を確認し、実装。
2. incremental Sleep、preview 無更新、出典範囲検証、キャンセルのテストから実装。
3. strict JSON / schema / LESSON のテストから出力検証を実装。
4. Retrieval の scope / ACTIVE / 件数 / tokens / 日本語検索のテストから実装。
5. CLI の4種類の Sleep と4種類の memory コマンドをテストして接続。
6. NEW / DUPLICATE / UPDATE / MERGE / SUPERSEDE / CONFLICT / IGNORE、再起動、同時実行、DB failure を検証。
7. 実 Chat Advisor + ContextAssembler の統合テストで Runtime Context の検索混入を検出して修正。
8. 部分設定時の既定値、永続化途中の interrupt、batch 上限、期限、検索範囲外の完全重複、補助記憶による hard limit 超過をテストして修正。

既存 enum の件数を固定していた3テストは、新 enum 値と旧値の両方を検証するよう更新した。既存テストの削除・無効化は行っていない。

初回全体実行ではユーザーデータ領域へのログ書き込みが sandbox に拒否され、既存 sqlite-vec テストのダウンロードも失敗した。検証専用 Data Directory を target 配下に設定し、依存ライブラリ取得を許可した実行で全テスト成功を確認した。実ユーザーデータの利用・変更を避ける構成とした。

最終全体テスト（2026-09-26、Maven exit code 0）:

| 区分 | 件数 |
|---|---:|
| 既存 | 2,471 |
| 新規 | 37 |
| 合計 | 2,508 |
| 失敗 | 0 |
| エラー | 0 |
| スキップ | 0 |

Surefire XML を集計。jqwik の生成試行回数はテスト件数に加算していない。新規37件は8クラスに分割し、外部 LLM はモック応答で検証した。実 API への推論リクエストは実行していない。既存 Context Compression / Session / Project / Working Set / cancellation / ChatExecution / SubAgent output validation も全体実行に含まれる。`git diff --check` も成功。

実行コマンド:

```powershell
$env:REI_DATA_DIR='F:\project\rei\target\memory-test-data'
.\mvnw.cmd -o '-Dmaven.repo.local=F:\project\rei\.m2\repository' test -q
```

## 非対象の確認

Auto Sleep、idle timer、Session-end trigger、cron / scheduler、日次・バックグラウンド Sleep は未実装。旧 auto-trigger 設定は保存の提案通知に限定され、SleepService を呼ばない。Cross-project 自動検索、外部 Vector DB、自動 forgetting / archive、Memory GUI も追加していない。

## 残る制約

LLM の意味判断の正しさは schema では保証できない。preview と source を使って確認可能。意味的照合の候補数・入力は有界であり、遠い言い換えの完全検出は保証しない。元履歴の途中削除・順序変更は想定しない。1 Turn が上限を超える場合は失敗し、黙って切り捨てない。
