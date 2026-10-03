# Work Context 実装報告

実装日: 2026-10-03。ブランチ: `feature/project-work-context`。
実装コミット: `5738ea525d215b253d5bff73f9fa4c2a59cd5dd8`。この報告は後続の文書コミットで追加する。

## 調査と方針

開始時の作業ツリーは clean。専用ブランチを作成した。リポジトリと親ディレクトリに AGENTS.md は存在しなかった。README、DEVELOP、既存の Session／プロジェクト状態／長期記憶の文書および実装を確認した。

ProjectRegistry の正規化された実ディレクトリと Project UUID、Session の固定 projectId を再利用する。既存の長期記憶／Manual Sleep は一般記憶を管理しており、Work Context は別テーブルに置くプロジェクト固有の状態とした。Sleep の完了や実行を必要としない。

## Phase 1

- 安定した項目ID、目的・現在作業・決定と理由・完了・未完了・検証・ブロッカー・次のアクション・成果物を構造化した。
- ユーザー、ツール、アシスタント、推定の取得元と確実性を分離した。Session／Turn／Run／Tool Call／ファイル／コミット参照は取得できた情報だけを保存する。
- 作成・更新・観測・取得日時、リビジョン、撤回・置換先、ユーザー訂正フラグ、処理済みRunを保存する。
- SQLite のトランザクションと期待リビジョンによる比較更新で、失敗時のロールバックと競合検出を行う。現在状態と全リビジョンを保持する。
- `/work`、`/work show`、`/work update`、`/work history` を既存 Shell コマンド体系に追加した。保存先は Session に固定されたプロジェクトであり、UIの選択変更では変わらない。未選択時は案内を返し、プロジェクトディレクトリを作成しない。

保存先は既存の `<rei.data-dir>/memory-consolidation.db`。`work_context_heads` に現在リビジョン、`work_context_revisions` に不変のJSONスナップショットを保存する。既存の記憶テーブルとは分離している。

## Phase 2

- 既存 MEMORY モデルとキャンセル方式で抽出し、既存の厳格JSONパーサと追加JSON Schemaで候補を検証する。抽出と統合・永続化は分離した。
- 会話・実行結果を指示ではなく参考データとして渡し、抽出時のTool実行を無効にした。入力・出力・時間を制限し、巨大ログを切り詰める。保存済み項目も入力予算内の投影として渡す。
- 項目単位で統合し、未登場の未完了項目を保持する。正規化一致で重複を抑え、訂正・状態変更・撤回・置換・矛盾を追跡する。古い観測や推定がユーザー訂正を上書きしない。
- アシスタントの完了報告から検証成功を推定しない。失敗・キャンセル・実行途中のタスク全体を確認済み完了にしない。
- Runの終端保存後に自動更新をキューへ投入する。既定では無効。失敗は別イベントで通知し、元のRun結果は維持する。同一プロジェクトの更新を直列化し、同じ終端Runの再適用を防ぐ。

## Phase 3

- Shell の選択・切替・新規Session時に短い引き継ぎを一度提示する。現在作業、進捗、未解決点、次のアクション、更新日時とGit条件を表示する。
- Chat Tool に取得、現在Sessionから更新、項目訂正、完了・再開・撤回、履歴・指定リビジョン取得を登録した。既存Toolイベント／権限方式を利用する。別プロジェクト操作には現在のユーザー指示内の明示的なUUIDまたはフルパスを必要とする。
- 通常チャットのAdvisorで現在状態だけを予算内に注入する。過去の参考状態と明記し、現在のユーザー指示を優先し、次のアクションの自動実行を禁止する。既存ContextAssemblerの最終予算に含め、必要なら省略する。
- 既存の認証付きWeb APIで取得・概要・履歴・更新を公開した。Native Clientは既存 WorkspaceOperation とHTTPアダプタから同じServiceを使用する。小さな引き継ぎカードと手動操作メニューを追加した。

## 利用例と設定

Shell: `/work update` で保存、`/work` で詳細、`/work history` で履歴。
会話: 「このプロジェクト、どこまで進んだ？」「今の作業状況を引き継ぎとして保存して」「この決定事項を修正して」「このタスクを完了／再開／撤回して」。項目IDと期待リビジョンで訂正する。

`rei.work-context` の初期値:

| 設定 | 初期値 |
| --- | --- |
| auto-update | false |
| auto-present | true |
| max-context-tokens | 1200 |
| max-input-tokens | 12000 |
| timeout-seconds | 120 |
| max-turns | 20 |

環境変数・API・保存形式の詳細は [利用ガイド](project-work-context.md) を参照。

## TDDと検証

永続化、統合、構造化出力、Service、終端ライフサイクル、提示、API／認証、LLM抽出、Shell、Nativeの順に小さい Red → Green → Refactor を繰り返した。後半の監査でも、矛盾による訂正上書き、入力予算超過、残留RUNNING Turnが新しい完了Runを妨げるケースを先に失敗させて修正した。詳細は `.kiro/specs/project-work-context/design.md`。

| 検証 | 結果 |
| --- | --- |
| Java 全回帰テスト | 2,626件、失敗0、エラー0、スキップ0 |
| Native TypeScript | 15ファイル・51件成功 |
| Native Rust | 78件成功 |
| TypeScript typecheck／lint／production build | 成功 |
| git diff --check | 成功 |
| 固定LLM出力の通しSmoke | 会話→抽出→保存→Repository再作成→新規Session提示／コンテキスト参照が成功 |
| 実LLM Smoke | 未実施。既定サーバー `http://192.168.1.50:11434` のモデル一覧確認が5秒でタイムアウト |

JavaはJDK25で `mvnw.cmd -o -Dmaven.repo.local=C:\Users\mikoto\.m2\repository test -q` を実行した。テストのデータ・ログは `target/work-context-test-data` と `target/work-context-test.log` に隔離した。既存sqlite-vecのダウンロードを必要とするテストはネットワーク許可環境で最終全件実行した。

ログ: `target/work-context-final-regression.log`、`target/work-context-client-final.log`、`target/work-context-rust-final.log`（生成物のためGit対象外）。実LLM確認ではモデル一覧の読取だけを試し、プロジェクト会話を送信していない。

## 判断事項と制約

- Shell／Chat Toolの明示更新は最新の実行中Turnの部分進捗も扱う。実行中Runは処理済みにせず、終端時に再更新できる。HTTP／自動更新は終端Turnだけを対象とする。以前の強制終了で残ったRUNNING記録は後続更新を妨げない。
- Git情報はRun開始時に取得してTurnへ保存する。古いTurnで取得できない場合は更新時の取得値を用いる。取得失敗でも参照可能とし、別ブランチ／コミット／Git未確認の条件を表示する。
- 重複抑制は正規化一致とLLMによる既存ID参照が中心。意味が似た別表現を常に自動統合する保証はない。
- 更新キューは単一ワーカー・上限256。同一プロジェクトの保存ロックとDB競合検出を併用する。強制終了直前の未処理キューまでの保存は保証せず、永続Resumeやプロセス復元は実装しない。
- 保存履歴は保持するが抽出は直近Turn・イベントと予算内の既存項目が対象。全履歴一括移行、常時Timeline取り込み、Auto Sleep、他エージェント自動委譲は対象外。
- Nativeの項目編集専用UIは追加していない。自然言語ToolまたはShellから利用する。Desktop／Mobile実機の手動E2Eは未実施で、型検査・ビルド・単体／HTTPテストまで検証した。

