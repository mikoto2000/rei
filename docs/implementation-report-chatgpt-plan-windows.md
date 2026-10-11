# Phase 4 Windowsネイティブ切替報告

2026-10-12。利用者の要望に合わせ、codex-app-serverの既定実行方式をWindowsネイティブへ変更しました。Windows Codex 0.162.1の実exeを直接起動し、native MXCを明示選択します。WSLはexecution-mode: wslの明示設定で維持します。弱いsandbox、WSL、CLI、HTTPへの自動fallbackはありません。

## 実装と再利用

WindowsPlanAppServerLauncherがWindows向けの起動、固定バージョン検査、限定環境、専用home/空workspace、MXC設定、所有プロセス停止を担当します。PlanAppServerPropertiesにexecution-modeを追加し、Spring設定bindingの既定をwindowsにしました。実exeにはdrive絶対パスを要求し、npm/cmd wrapperや未対応modeを拒否します。既存4引数のprogrammatic constructorは従来のWSL指定として維持し、設定YAMLのWSL利用には明示modeを追加します。

両launcherのbounded stdio、queue/出力上限、期限、プロセス終了はAppServerProcessSessionへ共通化しました。PlanAppServerRpcはWindows 0.162.1 / Linux 0.154.0の実schemaを固定し、期待するOS/versionを各launcherから受けて照合します。Windows以外や別バージョンではTurn前に拒否します。

Phase 4の共通backend/own OAuth、資料snapshot、Project/Session/Run、exact-input承認、role、追加推論予約・増分token計上、取消/unknown、schema・引用・範囲・厳密完了検証と手動/自動/並列routingは再利用しました。既存HTTP/local/CLI/Claudeの経路は維持します。app-serverは既定無効の読取レビュー用です。任意コマンド/編集/再委譲/永続resumeは引き続き提供しません。

## Windowsの権限境界

公式資料の最低ビルド条件を満たす実機26200.9550で、管理者setupや通常Codexの設定変更なしに検証しました。ビルド番号だけではMXCの利用可否を保証しないため、対応OSでも拒否されたら停止します。windows.sandbox=mxcを明示するため、prefer_mxcによるlegacy fallbackは使いません。

MXCの内部ファイル読取helperにはSystemDriveが必要でした。SystemRoot/SystemDrive/ProgramFiles/ProgramDataなど非秘密のplatform環境だけを明示して渡し、HOME/USERPROFILE/APPDATA/LOCALAPPDATA/TEMP/TMP/CODEX_HOMEは専用homeへ向けます。PATH/ComSpecはSystem32の固定値です。own OAuth tokenはapp-server子環境だけに渡し、sandbox環境の固定include_only allowlistから除外します。親のAPIキー・proxy・通常home・保存済みCLI tokenは継承しません。

root deny/minimal read/workspace read/network falseの名前付きprofileと、正確な起動binaryの追加readだけを使用します。MXCの必要OS/platform directoriesは読取可能ですが、実Projectは直接公開せず、guard済みsnapshotだけをdynamic toolが返します。shell、編集、Web検索、MCP、agents等を無効化し、未許可要求には停止確認後に拒否応答を返します。設定digestにはWindows実行方式・実exe・MXC/環境設定を含め、承認を再評価します。

MXC終了直後はdirectory handle解放が遅れるため、walkを閉じてから専用homeだけを削除し、最大2秒のbounded retryを行います。終了処理は起動したProcessとその子だけを対象にします。

## TDD・検証

- 新しいlauncher/設定契約を先にテストし、未実装のcompile REDを確認しました。
- 任意の認証不要実プロセステストでnative initialize、限定Thread作成、cwd/provider/profile、暗黙AGENTSなし、プロセス停止と専用home削除を確認しました。
- 診断fixtureでWeb検索が提示されるREDを発見し、Windowsセクションをglobal設定の後ろへ置いて修正しました。最終提示ツールはrequest_user_inputとrei_read_sourceのみで、request_user_inputは共通adapterが拒否します。
- [Windows診断](probes/codex_app_server_windows.py)で許可資料read成功、外部read拒否、write拒否、socket拒否（Windows error 10013）を実測しました。診断だけはPython runtimeの追加readを許可します。dummy bearer tokenの受け渡しもローカルHTTP fixtureで照合し、fixtureは推論を拒否します。
- 関連83件、failure/error/skip 0。追加は設定binding/直接起動環境3件、Windows handshake/reject2件、任意の実プロセス1件です。全回帰・CI・Gitの最終結果はignored .worktrees/chatgpt-plan-reports/windows.jsonへ保存します。

通常CIはmock/fixtureを使い、REI_TEST_WINDOWS_CODEXを指定した場合だけ実MXC起動テストを実行します。実OAuth/実モデル推論は行っていません。自然言語の真偽・任意自由文の完了は保証しません。利用方法・WSLからの移行は[ガイド](chatgpt-plan-subagents.md)を参照してください。

## 公式根拠とバージョン

[公式Windows sandbox資料](https://learn.chatgpt.com/docs/windows/windows-sandbox)のMXC条件・明示指定時の拒否・legacy差分を確認し、実0.162.1 binaryのexperimental schemaと実プロセス観測を根拠にしました。最新資料を旧0.154.0へ推測適用していません。通常インストールの0.154.0は更新せず、配布packageのSHA512を検証して専用領域に取得しました。検証用binaryは専用worktree削除時に片付けます。利用者は固定バージョンの実exeを別途用意してください。
