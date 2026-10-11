# ChatGPT Plan Phase 4 調査・停止報告

2026-10-11。**Phase 4の実行バックエンドは未実装です。** Phase 3のマージ `fec9d638608ae267d09e084e787719fdbe6c4024` とworktree削除を確認してから、専用ブランチ `feature/chatgpt-plan-phase4-app-server` / worktree `F:/project/rei/.worktrees/chatgpt-plan-phase4` を作成しました。

依頼書の停止条件「根本的な仕様上の問題、認証・権限上の危険、解消不能なCIエラーがある場合は、そのPhase以降を停止し、作業内容を保持して報告」に該当する、現環境の読取隔離の問題を実プロセスで確認しました。Phase 4を完了扱いにせず、調査・再現手順だけをdraft PRへ保存し、ブランチとworktreeを保持します。

## 再現した境界問題

インストール済みの `codex-cli 0.154.0` を、資格情報のない専用CODEX_HOME、空の新規workspace、明示的に限定した子環境で起動しました。標準入出力RPCの `initialize` / `initialized` は成功し、`permissionProfile/list` は下記の名前付きプロファイルをallowed=trueとして返しました。

```toml
default_permissions = "rei-probe"
[windows]
sandbox = "unelevated"
[permissions.rei-probe.filesystem]
":root" = "deny"
":minimal" = "read"
":workspace_roots" = "read"
[permissions.rei-probe.network]
enabled = false
[shell_environment_policy]
inherit = "none"
```

`thread/start` に permissions=rei-probe / ephemeral=true / approvalPolicy=untrusted / approvalsReviewer=user を渡すと、RPC error -32603で拒否されました。原因はWindowsのunelevated restricted-token sandboxがsplit filesystem read restrictionsを実施できず、unsandboxed実行を拒否したことです。Turnは開始していません。プロセスはstdin終了後にexit 0で終了しました。元の既定選択でも同じ拒否を確認し、再現スクリプトでは方式を明示しました。

[`docs/probes/codex_app_server_permissions.py`](probes/codex_app_server_permissions.py) はバージョンを0.154.0に固定した認証不要の再現手順です。Python 3.12で構文確認と実プロセス再現を行いました。通常CIには実バイナリを要求しません。診断はcheckout配下のignored target/へ専用homeとreport.jsonを残し、native stderrは保存しません。

```powershell
python docs/probes/codex_app_server_permissions.py C:/absolute/path/to/native/codex.exe
```

終了コード2は上記Thread拒否です。modelTurnStarted=false / threadCreated=false / processExit=0を確認してください。npm/shell wrapperではなくnative exeを指定します。異なるバージョンは拒否するので、対応スキーマを再調査してください。

`readOnly` という旧sandbox指定だけではProject外読取の防止を証明できません。全ディスク読取許可、danger-full-access、親のdenyの無効化では回避しません。Codexのuntrusted承認方式にも承認なしで許可されるコマンドがあり、承認RPCをすべて拒否するだけで親Policyが全操作へ適用されたとは言えません。

これは**現在のnative Windows / unelevated方式で確認した制約**です。全OS・全sandbox方式でapp-server統合が不可能という結論ではありません。elevated sandboxは管理者によるsetup・OSアカウント/ファイアウォール設定を要し、専用homeへ自動構築していません。MXC/WSL/別バージョンの利用可能性と強制境界は未検証です。本人認証、CLIの保存済みトークン、通常Codex homeの設定変更は行っていません。

## 公式仕様と実バージョン

- [SIWC app-server](https://developers.openai.com/siwc/token-sharing-open-source/codex-app-server): own OAuth access tokenを子環境のenv_keyへ渡すprovider、stable clientInfo.nameとagent_name_hint、更新後の再起動/thread resume。Phase 0の認証・refresh・logoutと同期させる必要があります。
- [app-server protocol](https://learn.chatgpt.com/docs/app-server): initialize/initialized、thread/start、turn/start、ストリーム、承認、interrupt、usage、terminal status。
- [Permission profiles](https://learn.chatgpt.com/docs/permissions): 名前付きfilesystem/networkプロファイルと読取拒否。
- [Windows sandbox](https://learn.chatgpt.com/docs/windows/windows-sandbox): unelevatedはdeny-read非対応、elevatedは管理者setupが必要。現行資料にはMXCもありますが、0.154.0での利用可否は推測しません。
- [0.154.0 source](https://github.com/openai/codex/blob/rust-v0.154.0/codex-rs/sandboxing/src/windows.rs): restricted-token方式が読取制限を拒否する実装を確認しました。

`app-server generate-json-schema --out ...` と `--experimental` の両方を実バイナリから生成しました。標準ThreadStartParamsにはpermissionsがなく、実験スキーマには文字列profile IDとして存在します。初期化experimentalApi=trueが必要です。0.154.0の旧SandboxPolicy.readOnlyにはreadableRoots等がないため、現行資料の新しいJSONをそのまま渡す実装はしません。生成物・初回実プロセス記録はworktreeのignored target/に保持しています。

## 既存Codex委譲との比較と再利用計画

| 項目 | 既存実装 | app-serverで必要になる差分 |
|---|---|---|
| 認証 | Codex CLI自身の認証、PlanOAuth/ProtectedCredentialStoreは別経路 | Phase 0自身のtokenを限定子環境にだけ渡す。CLI資格情報を流用しない |
| 起動・出力 | ExternalAgentProcessRunner、期限/出力量/取消、CLI JSON/schema | stdio RPC、ID対応、bounded parser、native version/schema検査 |
| 依頼 | ExternalAgentCommand、DelegationService、明示ユーザー認可 | 明示backend選択。既存 `/agent codex` / Claudeを維持 |
| Project・Run | AgentRunContext、ToolPermissionGuard、RunRegistry、共有予算 | opaque thread/turnと同一親Run関連付け。ツール側にも強制する |
| ツール・承認 | Policy、ActionCapability、exact-input one-use approval | native command/file/permission要求を分類し両側拒否を優先。現在の読取隔離はblocker |
| 実装・変更 | IsolatedImplementationService、承認済み仕様、隔離作業、Self Patch Review | app-serverでも既存経路を使う。変更を無承認で反映しない |
| 継続・復元 | opt-in opaque native session、checkpoint/明示再レビュー | own isolated home、thread resumeのownership検査、refresh後再起動。結果不明を再送しない |
| 使用量 | ModelCallBudget、Run/Goal token予約 | turn usageを同じ予約へ計上、usage不明を成功計上しない |
| 結果 | action schema、SubAgent schema/source/completion検証 | turn/completedのstatus確認後に既存検証。RPC ACK/終了コードだけでは成功にしない |

新たな実行バックエンド、設定bean、コマンドオプション、認証連携、推論adapter、ツールの権限bridgeは追加していません。プロダクションJavaの変更はなく、既存CLI/Claude/通常会話への動作変更もありません。この調査ブランチでの全Java回帰は再実行せず、Phase 3の5,036件・失敗/エラー0・skip2とCI成功を基準として記録します。Phase 4の要求されたmock/実行/認証失効/承認/Run統合テストは未実装です。

## 再開条件

1. 専用認証homeを維持したまま、Project外・資格情報ファイルの読取、書込、ネットワークの境界を強制できるsandbox方式を準備・実測する。管理者setup等が必要なら本人/管理者が行う。
2. 任意コマンドや親Policyの範囲を自動判定できない場合は拒否する方針、または明示した既存guard経由のツールだけを使う構成を確定する。
3. 対応バージョンと生成スキーマを固定して、Phase 4のTDD、認証連携、Run/予算、取消・結果不明・結果検証、回帰、CIへ進む。

研究draft PRは未マージ、worktreeは保持します。Git metadataとPhase 2/3完了情報は [`chatgpt-plan-phase2-4-delivery.md`](chatgpt-plan-phase2-4-delivery.md) にまとめます。
