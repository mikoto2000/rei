# JUnitレポート診断 実装記録

## 変更

監査C20に残っていたreport読取のうち、保存済み単一JUnit XMLを既存ToolsのREAD Toolへ追加した。TestReportDiagnosisServiceはProject境界・byte/tree/case/evidence上限とXML外部参照禁止を担い、トップレベル報告値と実観測を区別する。ファイル識別情報とpartial/warningsを返し、現processの成功や根本原因は断定しない。既存process-log diagnosisは変更しない。

既存CredentialRedactorを使い、診断文のマスキング後に長さを制限する。system output／propertiesを返さず、固定nextActions以外の実行指示を作らない。新しいDB・Provider・LLMは追加しない。ToolPermissionPolicyにREAD、SubAgentの明示要求用allowlistへ追加した。

## 検証

初期RedはTestReportDiagnosisService未実装によるコンパイル失敗。追加の振る舞いRedではproperties内の擬似testcaseを誤って観測することを1 failure / 0 errorで確認し、suite階層の検査を追加した。初期Greenでfailure/error/skipped、報告値と観測値の不一致、ネストと欠落count、DOCTYPE・不正XML・Project境界・除外pathを確認した。

追加で診断24件・clip・認証情報除去、output／properties非複製、1MiB／深さ64／tree8192／1024case上限、不正count/root、空レポート・SHA更新、取消、実Tool callbackと捕捉Project、target/surefire-reports配下の読取・既存READ権限／SubAgent明示指定を検証する。

最終関連テスト（TestReportDiagnosisTest・BuildTestFailureDiagnosisTest・ToolPermissionPolicyTest・SubAgentConfigurationTest・ToolsTest）はPASS。全体回帰は3104 tests / 592 suites、failure/error/skip各0でPASS。Javaのみ変更し、Native/Reactは再実行していない。feature 2ac395cdをPushし、main 9ab70cdbへMergeした。Merge後関連テストもPASS、main Push済み。詳しい仕様・限界は[junit-report-diagnosis.md](junit-report-diagnosis.md)。
