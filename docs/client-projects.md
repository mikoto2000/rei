# クライアントごとの対象プロジェクト

`ProjectService` はプロジェクト登録情報を共有しますが、選択状態は保持しません。
Shell や API セッションごとに `ProjectClient` を一つ作成し、同じクライアントの
リクエスト間で保持します。クライアントの破棄時はその参照を解放します。

```java
ProjectClient client = projectService.newClient(); // セッション作成時

// 各リクエストを処理するスレッドで開き、必ず閉じる
try (var scope = client.open()) {
  projectService.cd(directory);
  ProjectContext selected = projectService.currentContext();
}
```

新しいクライアントは起動ディレクトリから開始します。相対パスはそのクライアントが
選択中のプロジェクトを基準に解決します。`cd` と `remove` はクライアントの
スコープがない場合は失敗し、他のクライアントの選択状態は変更しません。
`add` / `remove` の登録一覧は従来どおり共有です。`cd` の未登録ディレクトリの
自動登録も維持しています。他クライアントで選択中の登録を削除しても選択は維持され、
後でそのプロジェクトを解決すると再登録されます。

スコープはスレッドに自動継承されません。別スレッドへリクエストを渡す場合は、
そのスレッドでも同じ `ProjectClient` のスコープを開きます。
同じクライアントへの同時 `cd` は最後に反映された選択が残ります。
実行開始時に `ProjectContext` を確定し、非同期処理には既存の `AgentRunContext` /
`ExecutionScope` で渡してください。これらの実行所有権はクライアントの現在の選択より
優先され、途中で `cd` しても実行対象・保存先は変わりません。
`contextForOperation()` はこの実行所有権を返し、`currentProject()` / `currentContext()` /
`currentProjectOrStartupDirectory()` はクライアントの選択を返します。

クライアントも実行所有権もない処理では、パスは起動ディレクトリ、既存の
プロジェクトスコープ付きストレージはグローバル領域にフォールバックします。
WebAPI の入口では必ず認証済みセッションに対応するクライアントを束縛してください。
この変更には HTTP エンドポイントやセッション認証の実装は含みません。

Shell は一つのクライアントを保持し、コマンド実行スレッドとプロンプト表示に
束縛します。切替後の履歴・Working Set の表示復元は `ShellProjectCommands` が
担当します。`ProjectService` と `ProjectCommand.CdCommand` は Shell UI に依存しません。
API ではコマンドの Spring singleton を同時実行せず、サービスを直接呼び出します。
