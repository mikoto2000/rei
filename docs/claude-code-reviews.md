# Claude Code CLI による外部レビュー

`/agent claude review path/to/file.md` または当該Runで「Claude Code に path/to/file.md をレビューして」と明示依頼する。Claude用Toolは `requestClaudeCodeReview`。Codex用の既存command/Toolは維持し、providerをモデル引数で任意の実行ファイルへ置換しない。

## 利用準備とサブスクリプション

Claude Code native CLIをインストールし、利用するOSユーザーで `claude` を起動してclaude.aiのPro/Max等の対象アカウントでログインする。確認は `claude auth status`。CLIのインストール・ログイン・利用枠の確認はClaude Code側で行う。本変更では実CLIや有料モデルを実行していない。

設定は既定無効。`rei.external-agents.claude.enabled=true`（`REI_CLAUDE_ENABLED`）、`command=claude`（`REI_CLAUDE_COMMAND`）。Windowsはnative `claude.exe` をPATHまたは絶対pathで指定し、npmの `.cmd`/`.ps1` shimを実行しない。初期対応のCLI baselineは2.1.286以上。全体timeout既定5分（最大20分）、inactivity既定2分、出力既定1MiB（最大4MiB）。

[公式の認証資料](https://code.claude.com/docs/en/authentication)では、非対話 `-p` は環境変数のAPIキーがあるとAPIキーを優先する。本adapterはClaude用の子プロセスに限って `ANTHROPIC_*`、`CLAUDE_CODE_USE_*`、bare-mode切替を取り除き、`CLAUDE_CODE_OAUTH_TOKEN` と `CLAUDE_CONFIG_DIR` を維持する。ホスト環境・保存認証を書き換えない。CLI `auth status` の成功結果が `claude.ai` または `oauth_token` の場合だけレビューを開始する。API/cloud/未ログイン/不明結果は拒否し、APIへfallbackしない。

`--safe-mode` は通常認証を維持する。[公式CLI reference](https://code.claude.com/docs/en/cli-reference)に従い、サブスクリプション認証を使わない `--bare` は使用しない。CLIのプラン利用枠・組織設定・追加利用設定はCLI/account側の制約であり、れいのtokenカウンタが請求額やサブスクリプション上限を証明するものではない。

## 読み取り範囲とCLI制御

れいが現在Project/root内の既存ファイル/ディレクトリを事前検査し、固定上限のUTF-8 snapshotをstdinのJSONへ渡す。相対path・SHA-256・redact済み本文、必要なtask/contextのみ。最大32ファイル、各64KiB、本文合計256KiB、走査512entry、深さ16。秘密名・設定/cache/buildディレクトリを除外し、binary/不正UTF-8/外部リンク/読取不明/上限超過はレビュー開始前に拒否する。大きなrepository全体では対象を絞る。除外や既存credential redactionは任意機密の完全検出保証ではない。

Claudeは空の一時directoryから実行する。`--safe-mode`、built-in Tool空集合・MCP Tool拒否、MCP空config/strict、setting source空、session非保存、chrome無効、dontAsk、最大3turn、固定system prompt/JSON schema/JSON出力を指定する。repository/userの自動CLAUDE.md/skills/plugins/hooks/MCPを読み込まない。組織のmanaged policyはCLIの仕様どおり適用され、本adapterは管理者policyを解除するOS sandboxではない。

レビュー後に読み取った各ファイルの現在SHAを再照合し、変化したsourceを成功として返さない。Claudeが見ていないファイル・Git state・test実行を検証済みとしない。結果にはsnapshot限定のwarningを必ず付ける。CLIの直接書込・Tool実行・修正案/Apply・native session継続・Claude並列batchは本機能の対象外。

## 共通予算・履歴・取消

1 Runの外部委譲claimはCodex/Claude/既存Codex batchで共通。Claudeは単一Codexのlegacy budget opt-in設定に関係なく、親Run/Goalの回数・報告token予算を使用する。version/help/auth確認はモデル呼出として計上せず、実レビューCLI直前に1回予約する。CLI内turn数ではなく委譲プロセス1回を回数1とする。

完全な成功JSONの `usage` にあるinput/output/cache creation/cache readを各一回合計する。cache内訳を重複加算しない。負値・非整数・不明・重複key・切り詰め・失敗結果・overflowをtoken有効時にTOKEN_USAGE_UNKNOWNとして停止する。報告超過も保存前に計上する。費用予算はprovider報告後の停止であり、開始済みモデルのhard spend capではない。

既存SQLite `external_reviews` にagent列を追加し、既存rowはcodexとして保持する。Claudeはclaudeとして保存し、STARTED/終端eventにproviderを反映する。保存プロンプト・source本文・raw CLIログを追加しない。`listClaudeCodeReviews`/`getClaudeCodeReview` は現在Project/rootのClaude履歴のみを読み、Codex用Toolとnative continuationはClaude履歴を混同しない。

親Run取消・worker interruptは既存owned process runnerに伝播し、そのCLIの管理process treeのみを停止する。結果不明を自動再送せず、Run/Goal回数を返金しない。利用枠を使う実モデル呼出なしで、SQLite・mock native output・認証経路・予算・入力境界をテストする。
