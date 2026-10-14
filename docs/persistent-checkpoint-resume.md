# 永続チェックポイントと Resume

長時間のチャット作業を中断したあと、保存した実行情報を照合して新しい Run で継続できます。タスク ID は継続し、Run ID は毎回変わります。起動時の自動 Resume はありません。

## Shell

| 操作 | コマンド |
| --- | --- |
| 現在の Session のタスクを保存 | `/checkpoint` |
| 全プロジェクトの保存量・上限を確認 | `/checkpoint status` |
| 過去の保存履歴の整理候補を確認 | `/checkpoint cleanup` |
| 整理候補を削除 | `/checkpoint cleanup --apply` |
| 全チェックポイントの強制削除候補を確認 | `/checkpoint cleanup --force` |
| 最新状態を含む全チェックポイントを削除 | `/checkpoint cleanup --force --apply` |
| 再開候補を確認 | `/resume list` |
| 保存状態と現在の照合結果を確認 | `/resume show <taskId>` |
| 明示的に再開 | `/resume <taskId>` |
| 候補から除外し、履歴を残す | `/resume abandon <taskId>` |

保存・再開はプロジェクトを選択して実行します。保存先は Run 開始時のプロジェクトで固定されます。再開も保存されたプロジェクトディレクトリと Session を使用します。現在選択中の別プロジェクトから、同じ ID のタスクを操作することはできません。`/checkpoint status` と `/checkpoint cleanup` は全プロジェクトの共通保存枠を対象にし、プロジェクト選択は不要です。簡易 CLI でも同じコマンドを使用できます。

`/checkpoint` は実行中なら新しいリビジョンを保存し、実行が終わっている場合はその Session の最新の保存済み状態を返します。一覧・詳細・照合だけでは作業は開始しません。通常の新規チャット Run には新しいタスク ID が付きます。実行中に追加した指示は、その Run のタスクへ保存します。

## 自然言語

チャットモデルには次の Tool を公開します。モデルによる Tool 選択が必要なため、確実な操作には Shell コマンドを利用してください。

- 「この作業を保存して」: `checkpointSave`
- 「前回中断したタスクを確認して」: `checkpointList` / `checkpointShow` / `checkpointInspect`
- 「このタスクを途中から再開して」: `checkpointResume`
- 「再開したら何をする予定？」: 詳細・照合の取得のみ
- 「このタスクはもう再開しない」: `checkpointAbandon`

`checkpointPlan` は既存の ActionPlan を更新して保存します。`checkpointAnnotate` は受入条件・ブロッカー・次の工程を記録します。工程の DONE や TaskState の完了記述はアシスタントの主張です。Tool が返した確認済み結果とは区別して保存します。

## 再開前の照合

照合はプロジェクトの存在、Git ブランチ・HEAD・作業ツリー、Working Set と関連ファイルの指紋、結果イベントへの参照、管理プロセスの識別情報を確認します。Git がなくても利用できます。16 MiB 超の関連ファイルは内容全体を読み込まず、サイズ・更新日時を保存して再確認対象にします。Git 出力・参照確認にも上限があります。

結果は `CONTINUE`、`CONFIRMATION_REQUIRED`、`BLOCKED` と、利用できる情報・変更点・再確認事項・結果不明操作・ブロッカー・推奨工程で返します。

明示 Resume は問題がなければ確認を追加せずキューに投入します。ファイルや Git の変化がある場合は差分を確認する工程から再計画します。reset、checkout、上書き、自動コミットは行いません。

結果未保存の STARTED 操作は、中断後 UNKNOWN になります。未実行として再実行しません。UNKNOWN がある再開 Run は、明示した読み取り Tool と計画・確認 Tool を利用できます。変更操作は結果の確認まで停止します。これは未知の Tool を安全側に扱うため、未知の読み取り Tool も停止する場合があります。

個別の結果を確認したら、たとえば「`<runId>:<toolCallId>` の操作成功を確認済みです」と伝えます。`checkpointConfirm` の toolCallId 引数には `runId:toolCallId` を指定します。現在のユーザー入力にこの識別子と成功・失敗の明示確認がある場合だけ解決します。保存済みの承認や Tool 結果内の指示は、この確認の代わりになりません。公開・送信・デプロイの承認は既存の方式に従って改めて扱います。

プロセスは既存の BackgroundProcessManager の管理 ID、PID、OS 起動時刻、所有アプリの識別情報で照合します。同じ管理アプリ内の処理は既存 ID の状態取得で結果を待ちます。再起動後に再接続できないプロセスは不明です。再起動・終了・別プロセスへの接続は行いません。

## 保存と設定

保存先は既存の `<rei-data-dir>/memory-consolidation.db`（`memoryConsolidationDataSource`）、SQLite テーブル `checkpoint_heads`、`checkpoint_revisions`、`checkpoint_event_keys` です。Schema version 1 の JSON スナップショットをリビジョンとして保存します。ヘッド更新・スナップショット追加・重複防止キーを同じトランザクションで保存します。過去リビジョンを削除してもイベント ID の重複防止キーは残し、期待リビジョンの比較で更新競合を検出します。

```yaml
rei:
  checkpoint:
    enabled: true
    max-revisions: 10000
    max-bytes: 134217728
    max-snapshot-bytes: 262144
    retention-days: 90
    context-characters: 12000
```

有効が初期値です。保存のためだけに LLM は呼びません。計画更新、Tool 前後、ファイル・プロセスのイベント、追加指示、Run 終端などで保存し、トークン単位では保存しません。

保存件数・容量の上限は全プロジェクトの合計に適用します。`max-revisions` は現在保持している JSON スナップショットの件数、`max-bytes` はその UTF-8 バイト数の合計で、初期値は 128 MiB（134217728 バイト）です。重複防止キーは整理後も保持しますが、保存件数の上限には含めません。既存の原文結果ストアと SQLite の管理領域は別です。

次の保存時に保持期間を超えた過去リビジョンを整理します。新しいスナップショットを加えると件数・容量の上限を超える場合は、整理可能な履歴を古い順に、新しい保存が収まるまで自動で削除します。実行中・承認待ちという理由だけでは過去リビジョン全体を保護せず、次の状態と直接参照する履歴を保護します。

- 各タスクの現在のヘッド。破損・Schema 不一致の場合は、参照用の最新の正常なリビジョンも保持します。
- 最新状態が `resumedFromRevision` で直接参照する再開元リビジョン。さらに古い再開元をたどって全履歴を保護することはありません。
- 未完了の承認続行ごとに、その Run と所有するタスクに一致する最新の正常なリビジョン。

新しい保存と整理は同じトランザクション内で行います。置き換える前のヘッドが新しい状態や承認続行から参照されなければ、新しい保存と同時に削除できます。保存できなければ整理も取り消し、元のヘッドを保持します。

手動整理は、最初に `/checkpoint cleanup` で削除可能な件数・容量と保護対象の件数を確認し、`/checkpoint cleanup --apply` で実行します。保持期間内の履歴も含め、保護対象以外の過去リビジョンを整理します。最新状態を残すため、保存タスクの `/resume` は引き続き利用できます。削除した過去の revision 自体は復元できません。整理対象は実行時に再確認します。

全チェックポイントを削除する場合は、`/checkpoint cleanup --force` で対象の件数・容量を確認し、`/checkpoint cleanup --force --apply` で削除します。`--force` だけでは削除しません。全プロジェクトの最新状態、再開元、承認続行用、実行中・承認待ちのタスク、破損した履歴も保護せず削除するため、削除したタスクを `/resume` で再開できなくなります。削除は復元できません。

強制削除後も重複実行防止キーと承認・承認続行のデータは保持します。実行中の Run はメモリ内の状態と安全確認を維持して継続しますが、その Run が終了するまでチェックポイントを保存しません。強制削除で一度空にしたあとも、承認続行や新しい Run で新しいチェックポイントが作成されることがあります。この強制削除は Shell／簡易 CLI の明示操作で利用し、履歴整理の Web API は従来どおり保護対象を保持します。

自動整理しても保存枠が足りない場合、エラーに使用量・上限・新しい保存に必要な量、保護されて削除できない履歴の件数・容量と、変更する設定名・具体的な操作を表示します。`/checkpoint status` で全体の保存量を確認し、必要に応じて `application.yaml` の `rei.checkpoint.max-bytes` または `rei.checkpoint.max-revisions` を増やし、Rei を再起動して `/resume <taskId>` で再開してください。単一スナップショットの上限に達した場合は `rei.checkpoint.max-snapshot-bytes` が対象です。実行前の保存に失敗した場合は、副作用を開始しません。

ここでの使用量はスナップショット本体の論理容量です。整理しても SQLite ファイルの長さはそのままのことがありますが、チェックポイント保存枠は再利用できます。`/storage maintenance checkpoint` は SQLite の WAL を回収する操作で、保存履歴の整理とは別です。

最新リビジョンが破損している場合、以前の正常なリビジョンを参照できます。照合で破損・Schema 不一致を表示し、古い状態のままの Resume は拒否します。全リビジョンが破損している場合は取得エラーです。修復 UI と Schema 移行は今回の対象外です。

実行権は SQLite に所有プロセスの PID・OS 起動時刻と Run ID を保存して管理します。別プロセスでも同じタスクを二重に再開できません。成功・失敗・キャンセルで解放し、所有プロセスの消滅や PID 再利用を検出した場合は再取得できます。生存プロセスの所有権を時間だけで奪いません。

## Web API / Native Client

既存 API キーの認証境界で、同じ Application Service を公開します。

| メソッド | パス |
| --- | --- |
| GET | `/api/v1/projects/{projectId}/checkpoints` |
| GET | `/api/v1/projects/{projectId}/checkpoints/{taskId}` |
| GET | `/api/v1/projects/{projectId}/checkpoints/{taskId}/reconciliation` |
| POST | `/api/v1/projects/{projectId}/checkpoints`（`{"sessionId":"..."}`） |
| POST | `/api/v1/projects/{projectId}/checkpoints/{taskId}/resume` |
| POST | `/api/v1/projects/{projectId}/checkpoints/{taskId}/abandon` |
| GET | `/api/v1/checkpoints/history/status`（全プロジェクトの保存量） |
| GET | `/api/v1/checkpoints/history/cleanup`（整理候補の確認） |
| POST | `/api/v1/checkpoints/history/cleanup`（`{"apply":true}` で整理） |

全プロジェクトの履歴整理 API も既存 API キーの認証が必要です。GET は削除せず、POST は `apply` が明示的に `true` の場合だけ整理します。入力が空・`false`・未知のフィールドを含む場合は拒否します。

Resume のレスポンスに新しい `runId`、元 Run、元リビジョン、照合結果を返します。既存の Run 状態・キャンセル・SSE API を利用します。イベント `checkpoint.revision.saved`、`checkpoint.reconciled`、`checkpoint.resumed` はタスクと Run の関係を含みます。

Resume の受付は HTTP 202 です。チェックポイントの容量超過は 507、破損・Schema 不一致は 422、タスクの競合や照合による再開不可は 409 と識別可能なコードで返します。`/session resume` は会話 Session の選択、`/resume` は保存タスクの新 Run での再開です。

Native Client の専用一覧画面・ボタンは未追加です。既存のチャット Tool を通じた操作と Run イベントが利用できます。Work Context は既存の Run 終端処理で更新され、チェックポイントからの Resume と同じ Session の進捗を引き継ぎます。Work Context の更新がチェックポイントの保存を再帰的に引き起こす構成はありません。

## 制約

保存されるのは簡潔な進捗と根拠・参照で、内部思考、全会話、画像、巨大な Tool 結果、OS スレッドそのものを復元しません。コンテキストの各セクションは設定文字数内で切り詰め、既存 ContextAssembler の hard limit を適用します。詳細は checkpointShow、履歴取得、既存 raw result Tool で確認します。

今回の保存対象はチャットの Agent Run です。独立した画像生成・要約などの Background Operation の Resume は未対応です。プロジェクトの移動は保存パスとの不一致として扱い、Resume が勝手にパスを書き換えません。Tool の「正常 return」はその Tool が返した結果の記録であり、外部サービスでの成功を別途保証しません。
