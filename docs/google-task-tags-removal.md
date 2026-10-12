# Google Tasks のタグ引数・項目の削除

Google Tasks 連携では、作成・更新に渡されたタグと一覧のタグ条件は利用されず、返却する `Task.tags` は常に空でした。この未実装の引数・項目を本体と簡易 CLI から削除しました。

## 変更範囲

- `Task` の `tags`、Java の `TaskQuery` の `tag`、`TaskService.add` / `update` のタグ引数を削除。
- LLM ツール `taskCreate` / `taskUpdate` の入力スキーマと説明から `tags` を削除。`taskList` は従来どおり未完了タスクを取得。
- 本体と簡易 CLI の `/task add` / `/task list` の `--tag` を削除。指定するとエラーになる。
- 一覧・HTTP レポートは `ID | 優先度 | 期限 | タイトル` の4列とし、末尾のタグ列を削除。
- HTTP の直接追加 `POST /api/v1/google-tasks` とキュー経由の追加 `POST /api/v1/google-operations/tasks/add` の DTO から `tags` を削除。
- 直接一覧 `GET /api/v1/google-tasks` の `tag` 引数と、キュー経由の一覧 `POST /api/v1/google-operations/tasks/list` の `tag` 項目を削除。

タグなしの例:

```text
/task add Review paper --due 2026-10-20
/task list --due-before 2026-10-21
```

```json
{"projectId":"project-id","sessionId":"session-id","title":"Review paper","due":"2026-10-20","priority":3}
```

上の JSON はキュー経由の追加用です。直接追加では `projectId` / `sessionId` を省きます。

## 互換性と更新手順

本体と簡易 CLI を同時に更新してください。旧簡易 CLI は `--tag` を使わない追加でも `tags: []` を送るため、更新した本体では HTTP 400 になります。旧 CLI と新本体の組み合わせは利用できません。

`--tag` を使う既存スクリプトは引数を除去してください。HTTP クライアントは `tags` / `tag` の JSON 項目を除去してください。空配列や `null` を送る場合も未知フィールドとして拒否します。`StrictApiRequest` による未知の書き込み項目の拒否は維持します。

`/task list` や HTTP レポートを列数・末尾の区切りで解析する処理は4列に合わせて更新してください。LLM ツールのスキーマをキャッシュするクライアントは、新しい `taskCreate` / `taskUpdate` 定義を取得してください。

## データと対象外の項目

現在の Google Tasks 実装はタグを DB に保存していないため、DB 移行やタグデータの削除処理は追加しません。

優先度の保存・絞り込みも現在は未実装ですが、今回は変更しません。UI の `ManagedTask`、Rust の別用途の `TaskQuery`、memory / paper / skill / Today のタグ、JUnit の分類タグはこの変更の対象外です。

## 検証

実 Google API と LLM を呼び出さず、サービスのモック、生成される LLM 入力スキーマ、コマンド解析・表示、HTTP の DTO 検証、簡易 CLI の送信内容で追加・一覧・更新を検証します。完了・期限更新・削除とブリーフィングの既存テストも回帰確認の対象です。

本体と簡易 CLI の全体回帰テスト:

```bash
./mvnw test -Pfull
./mvnw -f terminal/pom.xml -pl cli -am test
```

Windows では `./mvnw` を `.\mvnw.cmd` に置き換えます。既存の依存キャッシュを使う場合は `-o` を指定できます。`-Pfull` は実外部環境を使う `live` テストを除外します。
