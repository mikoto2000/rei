# External Agent Delegation (Phase 1)

明示的に Codex にレビューを依頼したときだけ、現在のプロジェクトに対して外部レビューを実行します。

```text
Codex にこの設計をレビューさせて
今の案について Codex の意見も聞いて
/agent codex review
/agent codex review docs/web-api.md
```

`/agent` の agent は `codex`、action は `review` のみです。未知の値は usage / unsupported エラーになります。
target は存在するプロジェクト内のパスに限定し、正規化前後および symlink 解決後に検証します。
省略時は Working Set のパス、抽出した設計決定、repository の順で対象を選びます。
自然言語の実行許可は現在のユーザー要求だけを確認する保守的な日本語・英語の判定です。
単なる Codex への言及や否定は許可しません。認識されない表現には `/agent` を利用してください。

## 設定

通常の Spring 設定 / Rei の外部設定ファイルに追記できます。変更後は再起動してください。

```yaml
rei:
  external-agents:
    codex:
      enabled: true
      command: codex
      total-timeout: 20m
      inactivity-timeout: 5m
      max-output-bytes: 4194304
```

`command` は一つの実行ファイル名またはパスです。引数付き shell string ではありません。
Windows で npm の `.cmd` / `.ps1` shim しか PATH にない場合は Codex のネイティブ `.exe` を指定してください。
毎回独立した ephemeral session とし、resume は使いません。同じ rei run の外部委譲は最大1回です。
global concurrency の追加設定は Phase 1 では提供しません。

## CLI の安全境界

実行時に `codex exec --help` で必要な capability を確認します。単なる旧式の `--sandbox read-only` だけでは
プロジェクト外の読み取りを制限できないため、現在の permission profile と isolated configuration を必要とします。
`--ignore-user-config`, `--ignore-rules`, `--strict-config`, `--ephemeral`, `--json`, `--output-schema`
を持たない CLI は起動拒否します。ローカルで調査した 0.106.0 はこの条件を満たしません。
profile 設定に未対応の CLI も strict config で失敗し、制約を緩める再実行はしません。

- approval: `never`
- default permissions: `rei_review`（組み込みの full-read profile は継承しない）
- filesystem: `:minimal` の実行環境に必要な読み取りと `:workspace_roots` の読み取りのみ
- `.env`, `.env.*`, `*.pem`, `*.key`, `credentials.*`, `secrets.*` を deny
- sandboxed command の network: disabled
- user config / exec rules の読み込みを無効化。MCP、plugin、hook、memory、goal、通知、Web 検索、複数エージェントは無効化
- generated shell への環境変数の継承は core に限定。CLI の履歴保存も無効化
- repository の指示ファイルを自動注入せず、レビュー用テンプレートで repository と context を untrusted data と定義

`:minimal` は shell / OS ライブラリ等を使うための例外であり、ファイルシステム全体の読み取り権限ではありません。
Codex 自体のモデル接続・認証通信は sandbox 内コマンドの network 制限とは別です。
管理者が設定した CLI / OS の安全境界を前提とします。実アカウントを使うレビューは自動テストでは実行しません。

確認資料: [CLI reference](https://learn.chatgpt.com/docs/developer-commands?surface=cli)、
[configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference)。

## 内部構造

`ExternalAgentDelegationService` は認可、project/target 検証、context 選択、run の1回制限、イベントを担当します。
`ExternalAgentExecutor` は外部実行の境界、`CodexExternalAgentExecutor` は CLI 引数、capability check、
prompt/schema、JSONL 結果の変換を担当します。既存 `SubAgentRunner` / `delegateTask` は変更しません。

`requestCodexReview(task, target?, context?)` は Workflow Tool です。agent や cwd を LLM に指定させません。
Slash adapter は既存 Picocli と非同期 `ConversationInputRouter` を使います。通常の run 内で同じ service を呼びます。
チャット実行中の slash request は独立した次の run として待ち行列に入れます。
結果は通常の rei 回答生成へ渡します。外部出力の指示は実行せず、rei が採否・根拠を評価して回答します。

context は今回のユーザー要求、レビュー焦点、必要な設計決定、最大20個の安全な Working Set パスです。
ファイル本文や全会話履歴を収集しません。`CredentialRedactor` と文字数上限を適用します。

## プロセスと失敗

`ExternalAgentProcessRunner` は引数リストで起動し、stdin writer / stdout reader / stderr reader を並行実行します。
stdout + stderr の保存バイト数を制限し、超過後も drain して deadlock を防ぎます。truncation は warning になります。
total timeout と inactivity timeout は monotonic clock で監視します。stdout または stderr の受信で inactivity を更新します。
cancel、timeout、正常終了、異常終了の cleanup で記録済み descendants を終了し、親を終了、stream と worker を閉じます。
run の cancel は既存の `CommandCancellationService` の child hook と run 状態を介して伝播します。

`ExternalAgentResult` は status、summary、findings、warnings、duration (ms)、exitCode、rawOutput を持ちます。
Finding は severity、title、reason、recommendation、nullable location です。
JSON parse 失敗は `SUCCESS_WITH_WARNINGS` とし、bounded raw output は adapter の結果に保持します。
通常の評価には raw process log を除いた結果だけを渡し、最終レビュー本文を取得できた場合は fallback summary に利用します。

外部実行の unavailable / authentication / nonzero exit / timeout は結果値として rei に返します。
rei run 自体は継続し、Codex の結果がない理由と rei 自身の評価を回答できます。ユーザーの cancel は run を終了します。

イベントは `delegation.started`, `delegation.completed`, `delegation.failed`, `delegation.cancelled`。
各 invocation は delegationId を持ち、runId / projectId と関連付けます。terminal event は一つだけです。
Shell は `[delegation] codex completed: N findings` 等を表示し、stdout は配信しません。
イベントは型付き payload で既存 project event store に保存され、AgentUiProjection の親 run 状態は変更しません。

## Phase 2

実装・修正・commit/push の委譲、他社 agent、registry、複数 agent の並列実行、resume、自動委譲、自動再レビューは対象外です。
