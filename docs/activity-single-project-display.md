# Phase 3.8.3 allowSingleProject の表示セマンティクス

ブランチ: `feature/activity-daily-summary`。
開始 HEAD: `a031b4c`、作業ツリー clean。新規branchなし。
追加コミットIDは完了報告を参照。

## 原因

従来の `allowSingleProject=true` は、GROUP candidate のメンバー数条件だけを緩めていた。
600秒以上、scope内の審査済み作業候補に対するcoverage15%以上という条件は必須のまま。
全日・時間帯で同じconsolidatorを使っていても分母・観測量が異なるため、
深夜ではGROUPが採用され、全日・午前・午後ではPROJECTが残った。
そのPROJECTのdisplayTheme/labelはcanonical project名だった。

別Builder、raw alias再導入、未反映の運用設定が原因ではない。
運用設定は前回の依頼で作成済みで、今回の比較にもそのファイルを使用した。

## 正式な意味

`allowSingleProject=true`:

> canonical project が明示的にgroupのprojectsに所属する場合、
> Daily Summaryの表示名にはgroup.displayNameを使う。
> GROUP集約のduration / coverage / 複数project条件を満たさなくても適用する。

`false` または未指定では従来のGROUP採用条件を維持する。
設定なしはproject表示。built-in group / 推論 / 学習は存在しないため、
表示の根拠はユーザー設定のみであり、暗黙の優先順位は追加していない。

- **Membership**: `projects` がcanonical projectの明示所属を定義する。
- **Aggregation**: 600秒 / 15% / メンバー構成など既存条件でGROUP候補へ集約するかを決める。
- **Display normalization**: 集約されないPROJECTでも、明示trueなら表示名を解決する。

`themes` は所属の必須条件ではない。既存どおり、project未確定THEMEとの明示包含関係、
およびGROUPの表示順位を計算するtopic支持に使う。今回その意味は変えていない。
themesなしのDeepSeekも、project membershipだけで表示を解決する。

## 実装と適用順

```text
raw project
  -> ProjectNameNormalizer（alias）
  -> canonical project / 既存関連品質審査
  -> 明示 group lookup
  -> GROUP aggregation 判定
  -> 集約されなかった PROJECT の display resolution
  -> 既存包含抑制 / label重複除去 / final ranking
  -> 全体 / 主テーマ / 時間帯 / fallback / LLM
```

主要変更:

- `DailySummaryThemeConsolidator.resolveDisplay`: 既存final候補生成経路で表示を解決。
  別serviceや新しいsummary frameworkは追加しない。
- `SummaryThemeCandidate.displaySource`: PROJECT / THEME_GROUP / GENERIC_THEME。
  `level` は証拠の粒度で、表示名だけ変えた候補はPROJECTのまま。
- `DailySummaryAggregator`: 表示labelが変わっても、canonical identityで内部の強い関連を保持。
- `LlmDailySummaryWriter`: 内部provenanceを保持したaggregateから、表示専用入力を作る。

canonical project、groupIds、memberProjects、関連根拠は内部に残す。
保存済みActivityRecordのproject/category/serviceやSummarySegmentを書き換えない。
Timeline表示、Classification、EntertainmentDisposition、Behavior、日付構文は変更なし。
スクリーンショット読込・Vision・Extractionは実行しない。

## Label と集計

表示名の後には観測されたactivity categoryを最大2件使う。

```text
sensevoice-input + documentation/development
  -> 音声入力・文字起こし系の文書作業・開発

livevingo + development（strong topic=翻訳）
  -> 音声翻訳系の開発

deepseek-vl-flash-vision-exp + development
  -> DeepSeek / Vision関連の開発
```

表示groupへtopicを再連結しないため「音声翻訳系の翻訳」としない。
categoryは保存済み主活動に由来し、翻訳というtopicから開発を新しく推測してはいない。
表示名が既に同じcategory表現で終わる場合も二重付加しない。

置換は同一候補のdisplayTheme / displaySource / labelだけ。
duration、observationCount、連続性、associationConfidence、specificity、score、
canonical member、levelを保つ。PROJECTと表示用GROUPを二つ生成しない。
既存のGROUP集約条件・スコア式は変更しない。

親子抑制は同じgroupIdsを使うため、表示を変えても既存のTHEME包含抑制が働く。
異なるprojectが集約条件を満たさず同じ表示labelになる場合、
既存のlabel重複除去で高scoreの代表1件を残す。
代表の観測量へ他projectの秒数を暗黙に足さない。
全体のcategory/disposition/観測秒数の集計には元の観測が残る。

## LLM / fallback

LLMには全日・各bucketの表示解決済みlabel/displayTheme/displaySourceを渡す。
THEME_GROUP表示の候補ではcanonical名を含むid、memberProjects、topic再付加につながるthemesを送信から除く。
bucketのdominantProjectsも除き、表示名を解決したprojectのstrongAssociationsは内部にだけ保持する。
前回までのtopProjects / majorWorkBlocks.theme除外も継続する。
GROUP採用時のcanonical memberも内部provenanceとして保持するが、LLMには列挙しない。

プロンプトにTHEME_GROUPのlabelをそのまま使う制約を追加。
LLMにdisplay解決・groupingを委ねず、成功・不正応答・例外fallbackで同じ最終候補を使う。
通常PROJECT表示（未設定・falseでGROUP不採用）の名前は引き続き使用できる。

診断はDEBUG `[summary-theme-display]` でcanonicalProject / group /
allowSingleProject / resolvedDisplayを確認できる。INFOには出さない。

## TDD と fixture

追加 `SingleProjectDisplayTest` は8ケース（parameterizedの3件を含む）。

最初の6ケースは修正前にすべてFailとなり、表示名・LLM入力の漏れを再現した。
修正後6ケースは成功。従来のDeepSeek raw表示を期待した既存テスト1件は、
新仕様どおりのdisplay期待へ更新した。
複数弱メンバーの重複除去、従来Phase3.8.3 fixtureの短さも追加し、
関連49ケースすべて成功。

低coverage fixtureは、全日8,100秒のうちsensevoice-input 1,080秒、livevingo360秒、
DeepSeek300秒、rei360秒、project未確定topic6,000秒。
speech-inputはcoverage不足、他2groupは600秒未満。
4bucketのすべてで表示を確認し、Main Work Themes / overview / fallbackでもproject名が残らない。

検証範囲:

- true / false / 未指定 / configなし
- theme match不要のDeepSeek
- alias適用後の表示解決・表示名hot reload・カスタムdisplayName
- seconds / observation count / score / association confidence不変
- GROUP集約とPROJECT display normalizationの粒度区別
- 同一labelの二重候補なし、親子包含抑制
- 実ChatModel境界のJSONにcanonical名が残らないこと
- LLM success / invalid output / thrown failureからのfallback
- 保存projectはrawのまま、既存Phase3.8.3 fixtureの出力500文字未満

## 実保存データの比較

前回の「ローカル確認のみ」の指定を継続し、外部LLMに送信していない。
稼働JVMのtimelineから2026-09-24の保存済みSummarySegmentだけを取得し、
修正済みクラスのDailySummaryServiceに、実際の運用設定
`C:/Users/mikoto/AppData/Local/Rei/activity/project-aliases.yaml` を渡してローカル再生した。
稼働serviceや設定、保存データは変更していない。

対象: 626区間、観測83,762秒。変更前も同じ日・同じ設定のローカル再生。
全日・時間帯の観測値は不変。

| project名の表示回数（全出力） | Before | After |
| --- | ---: | ---: |
| sensevoice-input | 4 | 0 |
| livevingo | 3 | 0 |
| deepseek-vl-flash-vision-exp | 1 | 0 |

| group表示名の使用回数 | Before | After |
| --- | ---: | ---: |
| 音声入力・文字起こし系 | 1 | 5 |
| 音声翻訳系 | 0 | 3 |
| DeepSeek / Vision関連 | 0 | 1 |

主テーマ After:

```text
- 音声入力・文字起こし系の文書作業・開発
- 音声翻訳系の開発
- rei の開発
```

時間帯 After:

- 深夜: 音声入力・文字起こし系の文書作業・開発、DeepSeek / Vision関連の開発
- 午前: 音声入力・文字起こし系の開発・文書作業、rei の開発
- 午後: 音声入力・文字起こし系の文書作業、microsoft-store-developer-onboarding の開発
- 夜: 音声翻訳系の開発

全体overviewにも音声入力・文字起こし系／音声翻訳系を使用。
全文字数は **715→663**。LLM向けstructured inputは **7,425→6,603文字**。
新入力にsensevoice / livevingo / deepseek-vl-flash-vision-expが残らないことを確認した。

非コミット生成物:
`target/runtime-audit/display-segments.json`（ローカルmetadata）、
`display-after.txt`、`display-after.aggregate.json`、`display-after.llm-input.json`。
Beforeは前回の `actual-example.txt` / `actual-example.txt.aggregate.json`。
実際の対話UIで新コードを実行した結果ではなく、同じ保存データと実serviceによるローカル再生である。

## 全検証・配布JAR

2026-09-25、JDK25 / Maven offline。追加8件を含む結果:

| 対象 | 初回結果 | ログ（target内） |
| --- | --- | --- |
| Java 全体 | 2,449件、2,446成功、2 failure、1 error、skipなし | single-display-java-all.log |
| Activity 関連 | 442件すべて成功 | 同上 |
| Java error の単独再実行 | 1件成功 | single-display-java-retry.log |
| Client | 42件成功 | single-display-client.log |
| Client型検査・build | 成功 | single-display-client-build.log |
| Rust | 66件成功 | single-display-rust.log |
| E2E | 10成功、2件page.goto 30秒timeout | single-display-e2e.log |
| E2E失敗分再実行 | 2件成功（workers=1） | single-display-e2e-retry.log |

Java failure は既存 WebBoundaryTest の
heartbeatHasNoSequenceAndDisconnectReleasesListenerAndTimer と
sendIOExceptionUnsubscribesAndDoesNotCancelRun（listener数の期待差）。
error は既存 ToolsTest.runCommandAutoPromotesSameLongProcessWithoutStartingTwice の
starts.txt 読み取り時のWindows共有違反。該当1件だけ再実行して成功。
対象のSSE / shell実装は変更していない。初回失敗を全件成功として扱わない。

E2Eは desktop/mobile の text and events are visible inline in output order が初回timeout。
該当2件の再実行は両方成功したが、flakyとして初回結果も記録する。

一時POMで出力先をtarget/single-display-buildへ隔離してclean package。
前回調査用settingsでcentral/nexusのoffline cache IDを解決。
REI_DATA_DIRはtarget/display-test-dataへ隔離した。
既存失敗があってもpackageを検証するためmaven.test.failure.ignore=trueを使用し、
テスト結果とBUILD SUCCESSを区別した。一時POMは削除し、恒久build設定は変更なし。

生成JAR:
`F:/project/rei/target/single-display-build/rei-0.0.1-SNAPSHOT.jar`

起動先:
`F:/project/rei/target/rei-0.0.1-SNAPSHOT.jar`

起動先へコピー済み。全1,141 classのclean buildとの一致と、コピー元/先のJAR SHA-256一致を確認:

```text
78DB89D04E54A617F7B0BDD0673BBC78B279683A21251EFE33B3FEAD9594DA93
```

src/mainと通常pom.xmlのソースfingerprint:

```text
106DC23DCB5CC07A859AF46564280BF0080587BB6DA9251D9FCE5568B7F21B96
```

**対話プロセスの再起動は未実施。新しい表示を稼働中の「れい」で使うには再起動が必要。**
運用alias/groupファイルの変更は不要。
実LLMの文章品質は未検証（ユーザーのローカル確認指定を継続）。
表示名が変わっても既存の品質足切りやtop候補選択で落ちた活動を無条件に追加することはない。
