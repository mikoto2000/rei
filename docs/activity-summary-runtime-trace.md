# Daily Summary 実行バイナリ・Pipeline Trace 調査

2026-09-25。ブランチ `feature/activity-daily-summary`、
調査開始 HEAD `6628e71 Fix summary theme containment and structured input validation`。
開始時の作業ツリーは clean。追加コミットIDは完了報告を参照。

## 結論

**調査開始時の実行 JAR は前回修正を含んでいた。出力が変わらない主因は、
実行時 alias/group 設定が空であり、実ログでは LLM 失敗後の fallback が使われていたこと。**

この結論は設定ファイル例の有無から推測したものではない。
稼働 JVM の Spring Environment、実 DailySummaryService の設定 snapshot、
保存データを用いたローカル command 再生を確認した。
最新コードでも、明示設定が空なら異なる project 名を同一視せず、包含関係も推定しない。
設定例を追加しただけでは運用出力は変わらない。

## 実行中バイナリ

調査開始時:

- PID: **38180**、親 PID: **15856**
- 実行 JAR: `F:\project\rei\target\rei-0.0.1-SNAPSHOT.jar`
- JAR 更新時刻: **2026-09-25 15:50:57 JST**
- JVM 起動時刻: **2026-09-25 15:53:52 JST**
- 作業ディレクトリ: `F:\project\rei`
- 起動コマンド:

```text
"C:\Java\jdk-25\bin\java.exe" "-Djava.net.preferIPv4Stack=true" "-Djava.awt.headless=false" "-Drei.computer-use.diagnostics.enabled=true" -jar "F:\project\rei\target\rei-0.0.1-SNAPSHOT.jar" --fullauto --project F:\rei
```

親は `cmd.exe /c "...\start.bat --fullauto --project F:\rei"`。
start.bat / start.sh はともに script 自身のディレクトリの
`target/rei-0.0.1-SNAPSHOT.jar` を参照する。
`--project F:\rei` は作業project指定で、JAR の場所や Rei のデータディレクトリ指定ではない。

開始時 JAR SHA-256:

```text
B629BB850093B4E29C4851C05285146C4DEDEAA8094E133DB197DD312BFE640C
```

ZIP 内の DailySummaryThemeConsolidator / LlmDailySummaryWriter / SummaryThemeCandidate
の SHA-256 は当時の target/classes と一致。前回の groupIds、包含抑制、numeric検査修正を含む。
manifest は Java25 / Spring Boot4.0.4 / version0.0.1-SNAPSHOT。
git commit / build time metadata は元の JAR にないため、バージョン文字列だけで最新と判断せず、
クラス比較と JAR 更新後のプロセス起動を併用した。

判定:

| 可能性 | 確認 |
| --- | --- |
| 別ディレクトリの JAR | 該当しない。CIM / jps / jcmd / 親script が同じpath |
| 古い target の別名 JAR | 複数の旧JARは存在するが当該PIDは参照していない |
| コピー先だけ旧版 | 起動scriptは通常targetを直接参照。上記クラス一致 |
| ビルド後に未再起動 | 開始時は JAR 更新後に起動済み |
| resource/prompt取り違え | Summary prompt は LlmDailySummaryWriter の Java 定数。外部prompt resourceの再読込経路なし |

## 有効な設定と writer

JVM `rei.data-dir` と Spring Environment はともに:

```text
C:\Users\mikoto\AppData\Local\Rei
```

summary の path / llm-enabled / timeout / zone に property 上書きなし。
bound ActivityProperties と service snapshot は以下だった。

```text
project-aliases-file = activity/project-aliases.yaml
actual file = C:\Users\mikoto\AppData\Local\Rei\activity\project-aliases.yaml
file exists = false
project("sensevoice") = "sensevoice"
groups = []
llmEnabled = true
timeoutSeconds = 30
```

権限付き読み取りでもファイル未作成を確認。
一般 sandbox でのアクセス失敗を「未作成」の根拠にはしていない。
classification-rules.yaml は存在するが、summary alias/group の設定とは別物。

モデルの有効な既定設定:

```text
base-url = http://gx10-707e.local:8888
model = deepseek-v4-flash-vision-exp
```

実ログでは PID38180 起動後の **15:54:36.415** に
`Daily summary writing failed; using deterministic fallback (IllegalStateException)`。
これ以前の 15:51:57.739 にも同種の記録。
従来ログは例外型しか残していないため、timeout と断定はしない。
今回の DEBUG には writer mode / exception type / cause type / timeout判別を追加した。

実 LLM の再送信は自動承認レビューが private Activity の送信リスクを理由に拒否した。
宛先と8,542文字の入力を具体化して確認したところ、ユーザーが「ローカル確認のみで完了する」を選択した。
そのため実LLMへの再送信は行わず、ローカル確認で完了した。
本調査のデータ再生では外部 LLM を呼ばず、LLMの新しい実応答は取得していない。
retry は既存実装にない。

## 実行経路

```text
UserInputService / UserInputParser
  -> RootCommand に登録された ActivityCommand.SummaryCommand
  -> ActivityTimeline.trendSummary(date)
  -> ActivityDateArgumentResolver / ActivityQueryRange
  -> summaryBetween -> DailySummaryService.summarize
  -> DailySummaryAggregator
  -> DailySummaryThemeConsolidator（全日5件 / 時間帯2件）
  -> LlmDailySummaryWriter または DailySummary.fallback
  -> DailySummaryFormatter
```

日付省略 / today / yesterday / YYYY-MM-DD は同じ SummaryCommand と service を通る。
旧 summary()/trendSegments() API は残るが、SummaryCommand の呼出し先ではない。
ActivityConfiguration が ProjectAliasStore 入りの service を timeline に注入している。
whole-day と time-of-day は同じ consolidator を呼ぶ。
fallback が別の raw source を読み直す経路も、formatter が raw alias を再追加する経路もない。

## 実保存データでのローカル再生

稼働 JVM に一時診断エージェントを attach し、既存 Spring context の
設定・store・clock・summaryPolicy を読み取った。
**稼働 service を書き換えず**、新しい service / timeline / command インスタンスを作り、
writer をローカル deterministic fallback に限定して `summary yesterday` を実行。
比較側も新しい service に既存の設定例を指定しただけで、運用ファイルは変更していない。
DEBUG logger level は finally で復元。エージェント・実データの診断出力は target 内のみ、非コミット。

対象は 2026-09-24、**626 SummarySegment、83,762観測秒**。
現行設定でユーザー提示の全体・時間帯・主テーマの文章を再現した。

| stage | 現行 effective config | 既存設定例を診断用に指定 |
| --- | --- | --- |
| raw candidates | sensevoice / sensevoice-input の文書作業、livevingo 翻訳、project未確定 文字起こし・音声翻訳ほか | 同じ保存データ |
| canonical | sensevoice と sensevoice-input は別projectのまま | sensevoice 系を sensevoice-input へ統合 |
| grouped | mapping自体なし、groupIds=[] | speech-input / speech-translation / vision-model の明示mappingあり |
| group採用 | なし | 全日は支持条件不足でPROJECT維持。深夜は speech-input GROUP を採用 |
| suppressed | なし | 全日は2 topic、夜は音声翻訳を対応projectのgroup IDで包含抑制 |
| final | 複合THEME、sensevoice-input、sensevoice、livevingo、rei の5件 | sensevoice-input、livevingo、rei の3件 |
| writer | ローカル再生は FALLBACK。元の実ログも FALLBACK | ローカル FALLBACK |
| formatter | 下記の同じ DailySummary を受け取る | 同左、raw evidence 参照なし |

主テーマ Before:

```text
文字起こし・音声翻訳
sensevoice-input の文書作業
sensevoice の文書作業
livevingo の翻訳
rei の開発
```

ローカル比較 After:

```text
sensevoice-input の文書作業・開発
livevingo の翻訳
rei の開発
```

深夜は `音声入力・文字起こし系の文書作業・開発` と
`deepseek-vl-flash-vision-exp の開発`。
午前・午後は canonical sensevoice-input。夜は `livevingo の翻訳` の1候補。
全日の強い候補の観測秒は sensevoice-input 1,371秒、livevingo476秒、rei355秒。
group表示が全日では採用されないことは、支持条件を保つ既存設計どおり。
この調査で条件を緩めていない。

raw alias は「後段で復活」したのではなく、**alias未設定の正規化段階で別projectとして残った**。
親子重複も「再追加」ではなく、**group lookup が空のため抑制対象にならなかった**。
この違いが、バイナリ更新だけでは結果が変わらなかった理由。

| 指標 | 現行設定 | 設定例によるローカル比較 |
| --- | ---: | ---: |
| alias重複ペア（主テーマ） | 1 | 0 |
| livevingo / 音声翻訳の包含重複ペア | 1 | 0 |
| formatter全出力文字数 | 740 | 715 |
| structured input文字数 | 8,542 | 7,425 |

## LLM input / output / formatter input

実集約を保存し、**実装の structuredInput() を直接呼んで**送信前入力を確認。
target/runtime-audit の以下の非コミット生成物に具体値を保存した。

- actual-before.txt / actual-example.txt: command の全出力と有効設定
- 同名 + .aggregate.json: command経路の writer が受け取った実集約
- 同名 + .llm-input.json: 実 writer が生成する structured input（未送信）
- 同名 + .formatter.json: formatter に渡る deterministic DailySummary

現行設定では mainWorkThemeCandidates の PROJECT `sensevoice` が残る。
比較側では canonical 化され、複合THEMEとlivevingo childの二重候補はない。
入力には rawProjects / rawThemes / summarySegments / title / screenshot を渡さず、
topProjects と majorWorkBlocks.theme も前回修正どおり除外される。
canonical memberProjects は group の metadata として残る。

実 LLM output: **未取得**。元の実行は fallback のため有効な structured response は確認できない。
mock LLM の success / invalid / failure は integration test でそれぞれ検証。
formatter input は上記 .formatter.json と対応し、最終 CLI 文字列との差に raw 名の再導入はない。

## 最小変更

分類・alias・grouping・score・選択ロジックは変更していない。

- ActivityCommand: command/date/handler の DEBUG
- DailySummaryAggregator: raw/canonical theme trace
- DailySummaryThemeConsolidator: grouped/suppressed/final trace（理由・group IDs）
- DailySummaryService: source-segments、writer-mode、failure type、formatter-input
- LlmDailySummaryWriter: 入力生成を structuredInput に切り出し、検査済み input と検証済み output を DEBUG

`[summary-trace]` の command / source-segments / raw-themes / canonical-themes /
grouped-themes / suppressed-themes / final-themes / llm-input / llm-output / formatter-input を追える。
scope / scored / config は既存 `[summary-theme]` と併用する。
出力検証に失敗した場合は llm-output validated はなく、writer-failure / FALLBACK が出る。
通常 INFO には出さない。DEBUG は project名・要約本文を含むため診断時のみ有効化する。

## ビルド・反映・検証

起動中 JAR を clean で削除しないよう、一時POMで出力先のみ
`F:\project\rei\target\runtime-build` に変更して clean package を実行した。
ソース・依存・resource・test は通常POMと同じ。恒久的な build 設定は変更していない。
一時manifestに調査開始commitとソースfingerprintを記録した。

最初の offline 実行は clean / jar plugin の repository ID 差
（cache は central、既定mirrorは nexus）で解決失敗。
一時 settings に両IDを認識させた offline clean package で解決し、
全790 source fileを再コンパイル、Spring Boot repackageまで成功した。
一時POMは削除し、診断用 settings / ログは target/runtime-audit に保持。

- build JAR: `F:\project\rei\target\runtime-build\rei-0.0.1-SNAPSHOT.jar`
- build時刻: **2026-09-25 16:13:35 JST**
- 実行用コピー先: `F:\project\rei\target\rei-0.0.1-SNAPSHOT.jar`
- 旧実行JARの保管: `target/runtime-audit/before-runtime.jar`
- JAR 内全 **1,140 class** と clean build の classes のSHA-256一致を確認
- コピー元/先のJAR SHA-256一致:

```text
1444947B5FC085EE939428127BC49379D750CFF94901F17D2F3C99444E6A33CF
```

manifest の `Rei-Audit-Base-Commit: 6628e71` は調査開始時点を示す。
今回の追記を含むソースは `Rei-Source-Fingerprint` で識別する
（src/main と通常pom.xmlのパス順SHA-256一覧のhash）:

```text
5C371EDD234F4FFA7524DAA67E5FE5D5C609BD6E5718717836A0485DCD429439
```

**再起動は未実施。** 配置後も PID38180 / 起動15:53:52の対話プロセスが継続している。
新JAR配置を、ロード済みクラスの更新や再起動後の動作確認と混同しない。
現在の対話端末の入出力をこちらの実行ツールでは引き継げないため、セッションを強制終了せず維持した。
前回の機能修正は開始時から稼働していたが、今回追加したtraceを使うには
通常どおり `start.bat --fullauto --project F:\rei` で再起動する必要がある。

追加は SummaryRuntimeTraceTest の **7ケース**。
4日付構文で Slash parser→command→query→service→writer→formatter を通し、
設定なしの重複再現→hot reload後の禁止文字列不在を検証。
success / invalid / failure の3ケースは実 LlmDailySummaryWriter と mock ChatModel を使い、
実送信境界のinput、writer-mode、formatter-input、DEBUG限定の各stageを検査する。
最初はテスト用rootに注釈のないObjectを使い7件の初期化errorとなったため、
CommandSpecでrootを定義して修正した。その後7件成功。

| 対象 | 結果 | target/runtime-audit 内ログ |
| --- | --- | --- |
| Java 全体 | 2,441件中2,439成功、2失敗、error/skipなし | java-clean-final.log |
| Activity 関連 | 434件成功、追加7件を含む | 同上 |
| Client | 42件成功 | client.log |
| Client型検査・build | 成功 | client-build.log |
| Rust | 66件成功 | rust.log |
| E2E | 12件すべて初回成功 | e2e.log |

Java失敗は既存 WebBoundaryTest の
`heartbeatHasNoSequenceAndDisconnectReleasesListenerAndTimer`、
`sendIOExceptionUnsubscribesAndDoesNotCancelRun`。
前回と同じlistener数の期待差で、関連実装は変更していない。
`maven.test.failure.ignore=true` はこの既存失敗があっても
packageまで確認するために指定した。BUILD SUCCESSを全テスト成功とは扱わない。
REI_DATA_DIRはtarget/runtime-audit/test-dataへ隔離した。

残る未検証事項は、追加trace入りJARでの対話プロセス再起動、
実LLMの応答/IllegalStateExceptionの詳細原因。
運用設定の適用は別途必要であり、JARの再配置だけでは未設定時の出力は変わらない。

## 運用上必要なこと

[既存の設定例と適用手順](activity-consolidation-pipeline-fix.md#設定の適用) を参照。
example は built-in ではない。運用出力を統合するには実データディレクトリの
project-aliases.yaml にユーザー設定として適用する必要がある。
今回は「まず原因を確認し、いきなりruleを追加しない」という依頼に従い、
運用 alias/group の新規作成や自動追加はしていない。
