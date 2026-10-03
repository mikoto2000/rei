# 永続チェックポイントと Resume

長時間のチャット作業を中断したあと、保存した実行情報を照合して新しい Run で継続できます。タスク ID は継続し、Run ID は毎回変わります。起動時の自動 Resume はありません。

## Shell

| 操作 | コマンド |
| --- | --- |
| 現在の Session のタスクを保存 | `/checkpoint` |
| 再開候補を確認 | `/resume list` |
| 保存状態と現在の照合結果を確認 | `/resume show <taskId>` |
| 明示的に再開 | `/resume <taskId>` |
| 候補から除外し、履歴を残す | `/resume abandon <taskId>` |

プロジェクトを選択して実行します。保存先は Run 開始時のプロジェクトで固定されます。再開も保存されたプロジェクトディレクトリと Session を使用します。現在選択中の別プロジェクトから、同じ ID のタスクを操作することはできません。

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

保存先は既存の `<rei-data-dir>/memory.db`、SQLite テーブル `checkpoint_heads`、`checkpoint_revisions`、`checkpoint_event_keys` です。Schema version 1 の JSON スナップショットをリビジョンとして保存します。ヘッド更新・スナップショット追加・重複防止キーを同じトランザクションで保存します。過去リビジョンを削除してもイベント ID の重複防止キーは残し、期待リビジョンの比較で更新競合を検出します。

```yaml
rei:
  checkpoint:
    enabled: true
    max-revisions: 10000
    max-bytes: 67108864
    max-snapshot-bytes: 262144
    retention-days: 90
    context-characters: 12000
```

有効が初期値です。保存のためだけに LLM は呼びません。計画更新、Tool 前後、ファイル・プロセスのイベント、追加指示、Run 終端などで保存し、トークン単位では保存しません。

保持期間を超えた過去リビジョンを次の保存時に削除します。各タスクの最新の正常なリビジョンは完了・未完了を問わず保持し、破損した最新データも修復用に残します。件数上限は削除済みリビジョンの重複防止キーも含めます。容量上限は JSON スナップショット本体の合計です。既存の原文結果ストアと SQLite の管理領域は別です。上限に達したときは以前の状態を壊さず明示エラーにします。実行前の保存に失敗した場合は、副作用を開始しません。必要なら上限を増やしてください。自動のタスク履歴削除 UI はありません。

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

Resume のレスポンスに新しい `runId`、元 Run、元リビジョン、照合結果を返します。既存の Run 状態・キャンセル・SSE API を利用します。イベント `checkpoint.revision.saved`、`checkpoint.reconciled`、`checkpoint.resumed` はタスクと Run の関係を含みます。

Resume の受付は HTTP 202 です。チェックポイントの容量超過は 507、破損・Schema 不一致は 422、タスクの競合や照合による再開不可は 409 と識別可能なコードで返します。`/session resume` は会話 Session の選択、`/resume` は保存タスクの新 Run での再開です。

Native Client の専用一覧画面・ボタンは未追加です。既存のチャット Tool を通じた操作と Run イベントが利用できます。Work Context は既存の Run 終端処理で更新され、チェックポイントからの Resume と同じ Session の進捗を引き継ぎます。Work Context の更新がチェックポイントの保存を再帰的に引き起こす構成はありません。

## 制約

保存されるのは簡潔な進捗と根拠・参照で、内部思考、全会話、画像、巨大な Tool 結果、OS スレッドそのものを復元しません。コンテキストの各セクションは設定文字数内で切り詰め、既存 ContextAssembler の hard limit を適用します。詳細は checkpointShow、履歴取得、既存 raw result Tool で確認します。

今回の保存対象はチャットの Agent Run です。独立した画像生成・要約などの Background Operation の Resume は未対応です。プロジェクトの移動は保存パスとの不一致として扱い、Resume が勝手にパスを書き換えません。Tool の「正常 return」はその Tool が返した結果の記録であり、外部サービスでの成功を別途保証しません。
