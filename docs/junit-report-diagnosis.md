# 保存済みJUnitレポートの診断

`diagnoseTestReport(path)`は単一のProject相対JUnit XMLを読むREAD Tool。例:

```json
{"path":"target/surefire-reports/TEST-example.SampleTest.xml"}
```

Runが保持するProjectを使う。SubAgentでは定義で明示要求した場合だけ利用できる。追加LLM・test実行・修正・retry・DB保存は行わない。

testsuite/testsuitesのトップレベル報告countと、実testcaseの観測countを別々に返す。failure/error/skipped、最大24件の失敗テスト名・種別・message・detail、固定の確認手順を持つ。報告countが欠ける・一致しない・診断が切り詰められる場合はpartial/warningsを付ける。ネストしたsuiteもtestcaseを一度ずつ観測する。レポートに含まれるsystem-out/system-err/propertiesは結果へ複製しない。

返すpath・SHA-256・更新時刻・観測時刻は読んだファイルの識別情報である。保存済みレポートが現在Runの結果か、最新かは独立検証していない。成功countだけで現在のprocess／Goal成功を判定しない。生成途中のfileは読み取り前後の属性変更を拒否するが、原子的な実行結果証明ではない。

単一file1MiB、XML深さ64・tree8192 node、観測1024 case、診断24件。test名／type256文字、message512文字、detail2048文字。CredentialRedactorとprivate key block除去を切り詰め前に適用する。DOCTYPE・外部entity／schema・XIncludeを無効化し、不正XML・未対応root・不正count・上限超過を拒否する。取消を伝播する。

親path traversal・絶対path・symlink・Project外・秘密情報用pathを拒否する。既存のRepository Map除外基準を使うが、レポート配置のtarget/buildは明示読取を許容する。JUnitを出力するMaven/Gradle等で利用できる。その他の形式、レポート自動発見、複数file合算、任意suite metadataの評価、原因の証明・自動repairは今回の範囲外。

## 集合診断 follow-up

標準配置の自動発見・明示directory・複数fileの集合診断を[diagnoseTestReports](junit-report-discovery.md)として追加した。上記の自動発見／複数file未対応は初期実装時点の記録である。現在のprocess／Goal成功を判定しない制約は同じ。
