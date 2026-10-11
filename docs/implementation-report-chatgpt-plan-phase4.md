# ChatGPT Plan Phase 4 実装報告

2026-10-12。`codex-app-server` を既定無効の読取専用バックエンドとして追加しました。先に確認したWindows 0.154.0のunelevated sandboxの読取制限問題は、既存WSL2 Ubuntu-24.04のLinux sandboxを使うことで解消しました。管理者setup、通常Codexの設定変更、本人認証は行っていません。

## 実装と再利用

PlanInferenceにHTTPとapp-serverの差分を閉じ込め、既存ChatGptSubAgentBackendのProject/Session/Run、role、明示資料snapshot、exact-input承認、共有予算、schema・引用・scope・厳密完了条件の検証を再利用しました。`/subagent run`、delegateTask(s)でbackendを明示できます。既存 `/agent codex` とClaudeのCLI委譲は維持します。

新規コンポーネントはPlanAppServerProperties/Client/Rpc、WslPlanAppServerLauncher、CodexAppServerSubAgentBackendと共通SPIです。主要変更箇所はPlanConfiguration/Client、SubAgentRunner/Tools/Command、ParallelSubAgentDelegator、ToolPermissionPolicyです。

専用adapterは0.154.0の生成スキーマに固定し、initialize/initialized、permissionProfile/list、thread/start、turn/start、動的資料読取、usage、終端イベント、interruptを扱います。応答ID、Thread/Turn、バージョン、cwd、provider、権限、承認方式を照合します。ACKやプロセス終了だけでは成功にせず、正常なturn/completedと単一final_answerの後に共通検証を行います。Traceにはopaque Thread/Turn、資料読取回数、拒否要求、イベント数、interrupt要求を記録し、生RPCやtokenは返しません。

## 権限・認証・予算

- Phase 0自身のOAuth tokenだけを限定した子環境へ渡します。clientInfo.nameは登録時agent_name_hintと同じrei。通常のCLI資格情報や親環境を継承しません。
- 選んだaccount/modelを既存catalogで検査し、期限・logout世代を実行中も監視します。refreshは既存storeで行い、新しい依頼ごとに新規プロセス/Threadを作ります。
- 空の専用workspaceとhome、root deny/minimal read/workspace read/network falseの名前付きprofileを使います。起動用の正確なCodex binaryだけ追加読取許可します。実Projectは直接mountせず、既存guardで許可済みsnapshotのみrei_read_sourceが返します。
- 初期inputには資料metadataを送り、内容は各pathについて一度だけ取得できます。roleがreadFile/readMultiFileを許可しない資料付き依頼は送信前に拒否します。shell、編集、ブラウザ、MCP、再委譲を無効化し、native承認/入力/追加権限要求を拒否します。
- 初回と資料読取後の各追加推論を親Runの同じモデル回数予約へ計上します。role.maxStepsと共有予算の小さい上限を適用し、usageは累積値の増分として計上して最終値との二重計上を防ぎます。不明usageは不明として保持します。
- 拒否時は負の応答を返す前にTurn中断を確認し、追加推論を防ぎます。取消・期限でもinterruptを要求し、停止確認できなければOUTCOME_UNKNOWN。通信結果不明、認証、quota、権限エラーの自動再送・別課金先fallbackはありません。
- stdioフレーム、全出力、queue、イベント数、期限を制限します。終了時に所有home/PIDを照合して専用Linux process groupを停止し、専用homeだけ片付けます。外部結果中の資格情報は拒否します。

## 検証

関連テスト78件でfailure/error 0。RPC状態機械、終端判定、拒否後中断、取消、unknown、malformed/cross-thread、auth/quota、own OAuth lease失効、Run/予算、増分usage、手動/自動routing、無効時のfallback禁止、既存Plan互換を確認しました。通常CIはmock/fixtureを使い、実認証を要求しません。

別途REI_TEST_WSL_CODEXに0.154.0 Linux binaryを指定した任意の実プロセステストで、production launcherの起動、限定Thread作成、プロセス停止と専用home削除を確認しました。全回帰・CI・Gitの最終結果は[納品記録](chatgpt-plan-phase2-4-delivery.md)とignored .worktrees/chatgpt-plan-reports/phase4.jsonに保存します。

認証不要の[WSL診断](probes/codex_app_server_wsl.py)はローカルHTTP fixtureだけへ接続し、推論を拒否することで提示ツールを検査します。同じprofileで許可資料読取成功、外部ファイル読取拒否、書込拒否、socket拒否を実測しました。実OAuth/実モデル推論の成功は確認していません。

以前の[Windows診断](probes/codex_app_server_permissions.py)は履歴として残します。Windows unelevatedで限定読取拒否という観測は現在も有効です。全ディスク読取やdanger-full-accessでは回避しません。

## 制限と比較

app-serverはWindowsから明示WSL distro/固定Linux binaryを起動する読取・レビュー用です。任意コマンド、実Project編集、Git操作、独立した子agent、永続Thread resumeは提供しません。CLIによる既存の明示隔離実装と変更レビューは従来経路を使います。Phase 3のHTTP修復/再検討ループはapp-serverへ適用せず、単一Turn内の資料取得を上限内で扱います。roleのrequiredToolCalls/evidenceTools/semantic契約は省略しません。

自然言語の真偽、任意自由文の完了は保証しません。親が外部見解の採用・部分採用・不採用を独立判断します。対応バージョン以外は拒否するため、更新時は生成schemaとsandbox境界を再検証してください。

## 公式仕様

- [SIWC app-server](https://developers.openai.com/siwc/token-sharing-open-source/codex-app-server): own tokenの子環境受け渡しとstable client名。
- [app-server](https://learn.chatgpt.com/docs/app-server): Thread/Turn、承認、usage、interrupt。
- [permissions](https://learn.chatgpt.com/docs/permissions): filesystem/network profile。
- [Windows sandbox](https://learn.chatgpt.com/docs/windows/windows-sandbox): sandbox方式による読取制限の違い。

現行資料を0.154.0に推測適用せず、実binaryの通常/experimental schemaとプロセス観測を実装根拠にしました。permissionsとdynamicToolsにはexperimentalApi=trueが必要です。
