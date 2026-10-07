# WindowsのCheckpoint独立JVMテスト起動

2026-10-07の全体回帰でCheckpointRestartSmokeTestがCreateProcess error 206となった。
依存jarのclasspathを `java -cp` のコマンド行へ渡す既存方式がWindowsの長さ上限を超える。
これはCheckpointの復元成功ではなく、子JVMを起動できないテスト環境の失敗である。

独立branch `codex/windows-checkpoint-restart-harness` で、同じ完全classpathを
共有JavaFixtureCommandのJVM argument fileへ渡す。shell、wildcardによる依存の取り替え、
test skip、偽の成功receiptを追加しない。Probe/root/modeは個別にquoteしたargumentとしてargfileへ保存する。
production/API/DB/configは変更しない。親環境は変更しない。argfileは各テストのTempDir内に置く。

Redは全体回帰の実プロセス起動error 206。Greenでは独立JVMによる保存、終了、
再起動、変更検知、STARTED副作用の手動照合、新Runへのresumeをそのまま検証する。
同じ原因のBackgroundProcessManager、FailureDiagnosis、ExternalAgentProcessRunnerと
その子プロセスも同じhelperへ接続した。長classpathと引用符/空白/backslash/日本語の
literal引数を実JVMで検証する新テストは未実装compile Redから追加した。
argfileはlauncherのnative.encodingで保存する。UTF-8固定ではこのWindows環境の日本語引数が
文字化けするbehavior Redを確認した。JVMのfile.encodingとlauncherの読み取りを混同しない。
[Java commandのargument file仕様](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html)と
[native.encodingの仕様](https://docs.oracle.com/en/java/javase/25/intl/supported-encodings.html)を参照。
受付上限機能の検証を妨げる環境問題を先に直すため、Phase順の前提修正として分離した。
