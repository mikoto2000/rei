# 保存済みJUnitレポートの自動発見・集合診断

`diagnoseTestReports`はREAD Toolで、Runが保持するProject内の保存済みXMLを診断する。

```json
{}
```

directory省略時はProjectと子moduleディレクトリから、Mavenの`target/surefire-reports`・`target/failsafe-reports`、Gradleの`build/test-results`とその直下taskディレクトリを探す。これらのディレクトリ直下の`TEST*.xml`が対象となる。標準配置の参考: [Maven Surefire](https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html)、[Gradle Java testing](https://docs.gradle.org/current/userguide/java_testing.html)。build設定は評価せず、独自の出力先は推測しない。

```json
{"directory":"module/target/surefire-reports"}
```

明示directoryの場合は、その直下の`TEST*.xml`だけを対象にする。独自配置でも利用できる。directoryの空文字・絶対path・親参照・秘密情報path・symlink／Project外junctionを拒否する。rootは`.`で指定できる。自動探索は隠しディレクトリ・node_modules等を除外し、target/buildの内部は上記のレポート配置だけに進む。

探索はmodule深さ6、256folder、4096entry、32fileまで。file候補と子ディレクトリを安定した名前順で処理する。entry上限に達したディレクトリでは全entryを整列できず、それまでの観測に限定される。読取は単一診断の1MiB・XML深さ64・8192node・1024caseを再利用し、最大32file。全体10秒の協調停止を探索・file読取の間で確認し、取消を伝播する。XML解析中の強制割込みではない。

結果はdiscoveredPaths、解析したfile毎のSHA-256・更新時刻・観測時刻・reported/observed counts・partial/warnings、読めなかったfileの固定reason、path付き失敗証拠（集合全体で24件）を返す。認証情報除去・DOCTYPE／外部参照禁止等は[単一XML診断](junit-report-diagnosis.md)と同じ。parserメッセージやsystem-out/propertiesを出力へ複製しない。探索対象ディレクトリが変化した場合はpartialとし、そのディレクトリの候補を採用しない。

counts合計は解析済みfileだけを対象にlongで保持する。reportedが欠けるfileがあればreported合計はnull、観測できたcaseのobserved合計は保持する。読めたfileが0ならreportedはnullでpartial。上限・探索／読取失敗・個別診断partial・証拠の切詰めをcollectionのpartialへ伝える。読めないfileや未探索fileを成功／0件と補完しない。

同じtestが複数fileにある場合も別々に数える。異なるRunの混在・重複・最新性・現在processの成功を判定せず、集合は原子的snapshotでもない。partialがfalseでも現在RunやGoalの成功証明にはならない。時刻・path・実行結果を照合し、必要なfileは既存diagnoseTestReportで個別に確認する。

追加LLM・test実行・修正・DB保存・外部通信は行わない。SubAgentでの利用は定義の明示要求が必要。その他report形式・原因の証明・自動repairは引き続き別対応。
