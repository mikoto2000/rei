# JUnitレポート自動発見・集合診断 実装記録

## 変更

監査C20の保存レポート自動発見・複数file合算の不足を、diagnoseTestReports READ Toolとして実装した。directory省略時はMaven/Gradle標準配置をmodule深さ6・256folder・4096entry・32fileで探索する。明示directoryは直下のTEST*.xmlを読む。既存単一XML parser・Project境界・認証情報除去を再利用し、個別SHA/時刻/count/partialを保持する。

件数合計はlongで、解析済みfileの観測だけを返す。欠落count・読取失敗・探索不足・24件の集合証拠上限・空集合を区別する。重複file／異なるRunの混在を除去したと主張せず、現在processやGoal成功を判定しない。共有10秒の協調停止と取消を伝播し、SubAgent明示要求・READ Policyへ接続した。追加LLM・DB・test実行・編集はない。

## 検証

初期Redは集合診断Service未実装によるcompile失敗。Maven unit/integration・Gradle task・複数moduleの標準配置、配置外／node_modules除外、明示directoryの範囲、壊れたXML／DOCTYPEの個別拒否とpartial、redactionと各file identityの最小Greenを確認した。

追加テストで33件のreport上限・24件の集合証拠上限、missing counts・空集合・重複testの非dedup、2999999997件のlong reported合算・個別1MiB拒否、module深さ・256folder・4096entryの上限、開始前取消とinterrupt保持、実Tool callbackの省略引数／捕捉Project、READ Policy・SubAgent明示要求を検証した。実Windows junctionを一時領域に作成し、Project外のfile名／内容を返さず、明示directoryでも拒否することを確認した。junctionはテストで解除済み。

最終関連テスト（TestReportCollectionDiagnosisTest・TestReportDiagnosisTest・ToolPermissionPolicyTest・SubAgentToolPolicyTest）はPASS。全体回帰は3130 tests / 595 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactは再実行していない。

feature d3f36e73をPushし、main a811ac66へMergeした。Merge後の関連テストもPASS、main Push済み。利用範囲は[junit-report-discovery.md](junit-report-discovery.md)を参照。完全原因特定・その他report形式・自動repairは引き続き残件。
