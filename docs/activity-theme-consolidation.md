# Phase 3.8.3 — Summary Theme Consolidation

既存 `feature/activity-daily-summary` を継続。Summary 内のテーマ選択だけを拡張する。
Activity Classification、EntertainmentDisposition、Behavior、Vision、Timeline一覧・診断、
summary / today / yesterday / YYYY-MM-DD の構文は変更しない。
保存済み metadata の派生集約であり、Screenshot / Vision / Extraction の再実行や永続化は行わない。

## 3つの概念

- canonicalProject: 個別projectの正規化名。既存 ProjectNameNormalizer と明示aliasを使う。
- summaryThemeGroup: 明示設定された上位表示グループのID。projectの同一性や新たな関連を意味しない。
- displayTheme: ユーザー向けの名前。例: speech-input → 音声入力・文字起こし系。

`SummaryThemeGroups` がgroup設定を検証し、`DailySummaryThemeConsolidator` が
Phase 3.8.2 の品質審査済み ProjectThemeStat から最終候補を生成する。
別projectのidentityを統合せず、表示上の候補だけをまとめる。

`SummaryThemeCandidate` は id、displayTheme、level（GROUP / PROJECT / THEME / GENERIC）、
displaySource（PROJECT / THEME_GROUP / GENERIC_THEME）、groupIds、
memberProjects（内部診断用の観測された代表最大2件）、memberProjectCount、activities、themes、
durationSeconds、observationCount、longestContinuousSeconds、associationConfidence、
specificity、groupCoverage、score、label を持つ。
THEME はproject不明でも強い明示テーマを捨てないためのlevel。
associationConfidence はspecific project-theme関連の値であり、group所属の確率ではない。

## 設定

既存 `rei.activity.summary.project-aliases-file` に themeGroups を追加する。
既定値は REI_DATA_DIR 基準の `activity/project-aliases.yaml`。
[コピー元の設定例](testdata/summary-theme-groups.example.yaml) はaliasとgroupを両方含む。

```yaml
projectAliases:
  sensevoice-input: [sensevoice, sensevoiceinput, sensorvoice-input, sansvoice-input]
themeGroups:
  speech-input:
    displayName: 音声入力・文字起こし系
    projects: [sensevoice-input, whisper-live-transcriber]
    themes: [音声入力, 文字起こし]
  speech-translation:
    displayName: 音声翻訳系
    projects: [livevingo, my-translator, livetrans, palabra]
    themes: [音声翻訳, 翻訳]
  vision-model:
    displayName: DeepSeek / Vision関連
    projects: [deepseek-vl-flash-vision-exp]
    allowSingleProject: true
```

projects は必須・空不可。themes は任意で、project不明のtheme-only候補との包含関係を明示する。
themes が未設定なら、そのgroupのproject候補は統合するが、意味の類似だけでtheme-only候補は消さない。
allowSingleProject は既定 false。明示 true なら、group集約の支持条件を満たさない場合も、
所属projectのDaily Summary表示にdisplayNameを使う。証拠のlevelやscoreは変えない。
詳細は [表示セマンティクス調整](activity-single-project-display.md) を参照。
Built-in group / 自動group推論 / theme alias設定は導入しない。すべてユーザー設定なので
既定groupとの競合や暗黙priorityはない。未設定projectは従来のproject + theme/categoryへ戻る。

ProjectAliasStore は SummaryThemeConfiguration（aliases + groups）を一括で再読込する。
DailySummaryService の ProjectAliasStore constructor は一回のsnapshot取得を使う。
保存済みデータを受ける入口で alias → canonical project → group lookup → display label の順に適用。
alias変更時にはgroupのprojectsも再canonicalizeする。各層でraw nameを復元しない。

validation:
- YAML重複キー、未知フィールド、不正型、空displayName、制御文字、60文字超を拒否。
- group IDは小文字英字始まりの英数字・hyphen、最大60文字、最大50group。
- projects最大100、themes最大20。projectは既存品質フィルタを通る名前か明示alias。
- 同一group内で同じcanonical projectになるalias重複は一つに畳む。
- 同じcanonical projectが複数groupに属する設定は拒否。優先順位で曖昧に解決しない。
- 同じdisplayName（NFKC・大小文字等の正規化後）や同じ包含themeを複数groupへ割り当てる設定も拒否。
- themesは既存の明示topic語彙の日本語canonical名のみ。未知語や重複を拒否。
- 文書全体64 KiB上限、YAML alias展開禁止等を維持。
- 不正な変更時はalias/groupを両方とも前回の有効snapshotに戻す。片方だけ適用しない。
- ファイル削除・空ファイルでは両方を解除する。既存のprojectAliasesだけの設定も引き続き有効。

## sensevoice の再調査

集約入口、日次stat、時間帯stat、consolidator、fallback、JSONを確認し、
設定済みaliasからraw nameが復活する経路はテストでは再現しなかった。
JSON全体にも raw `sensevoice` / `sensorvoice-input` が残らないことを検証した。
この環境の既定 `%LOCALAPPDATA%/Rei/activity/project-aliases.yaml` は今回も未作成だった。
実行中アプリの独自data-dir / 設定override / 実データまでは確認しておらず、提示出力の原因とは断定しない。

alias未設定ではsensevoiceとsensevoice-inputの同一性を推測しない。
運用で統合するには上記の明示設定が必要。設定例はリポジトリに追加したが、
ユーザーの運用ファイルは自動作成・上書きしていない。
今回の修正は、設定を一括適用し、全体・時間帯・fallback・LLM入力で同じcanonical/consolidated候補を使うこと。
aliasだけで同一になった2行を「group内の2project」と誤って数えないテストも追加した。

## Group採用・順位

まず既存の観測秒数・回数・連続性・関連confidence・specificityによる品質審査を行う。
その後、top 5で切る前の全候補をconsolidatorへ渡す。aliasによる重複はこの時点で既に統合済み。

groupの採用条件はすべて以下を満たすこと:
1. 観測されたメンバーの合計600秒以上。
2. groupCoverage = メンバー観測秒数 / 審査を通った作業候補の観測秒数が15%以上。
3. 異なるcanonical projectが2件以上、かつ最大メンバーがgroup時間の90%以下。
   または、メンバー1件でallowSingleProjectがtrue。

1件だけが55分、もう1件が5分ならgroup化しない。
条件を満たさない場合はproject-levelの証拠を残す。ただしallowSingleProject=trueなら
表示名はgroup.displayNameへ正規化する（集約採用と表示名の解決を分離）。
日全体ではcoverage不足でも、その時間帯では条件を満たせばgroup化できる。

groupのduration・observationCountはメンバーの排他的主活動の合計。
連続性はメンバーの最長連続秒数の最大値を使い、別project間に新しい連続区間を捏造しない。
categoriesは保存済み秒数の合計で上位2件、かつgroup時間の20%または120秒（小さい方）以上。
強いspecific themeでもgroup表示ではcategoryへまとめ、個別projectへ新しいtopicを帰属させない。

project scoreはPhase 3.8.2のscoreを再利用。
strong associationがある場合だけ `0.9 + 0.1 × 平均associationConfidence` を掛ける。
group scoreはメンバーscoreの合計に `1 + 0.15 × groupCoverage` を掛ける。
加えて、明示themesで包含するtheme-only候補のscoreを、包含したtopic数の割合だけ加点する。
これは表示選択用の支持であり、projectのdurationやassociationConfidenceには加算しない。
複数groupの包含themesは競合不可なので、同じtopicを複数groupの加点に使わない。
短い候補の回数だけで長い活動を押しのけないよう、元の回数・連続性加点上限を維持する。

## 包含・重複抑制

条件を満たすgroup候補を生成したら、メンバーprojectの表示候補を置換する。
上位を実際に選択した場合だけ、設定themesに包含されるtheme-onlyの部分を除く。
「音声翻訳・フィード要約」の音声翻訳だけが包含される場合、フィード要約は残す。
除いたthemeを内部ID経由でLLMへ漏らさない。
包含抑制で空いた枠に別groupが入る場合も、同じ処理を収束まで繰り返す。

設定にない意味的包含関係や、名前が似たproject同士は推測しない。
同一labelを重複除去し、具体的候補があればgeneric-only候補は最後の選択から除く。
全体は最大5件、時間帯は最大2件。項目数を埋めるために根拠のないテーマは足さない。
GROUPと未設定PROJECTは混在可能だが、同じ活動を上位・下位の両方で列挙しない。
THEMEも、所属が不明な具体的活動を失わない場合には残る。

## 時間帯・fallback・LLM

日全体も時間帯も同じconsolidatorを使い、観測範囲と出力件数だけを切り替える。
fallbackとLLMは同じ選別済みlabelを使う。writer失敗時にchildやweak associationを復活させない。
Phase 3.8.2のProjectThemeAssociationと、その支持・confidence判定は変更しない。

DailySummaryAggregateにmainWorkThemeCandidates、BucketにtimeOfDayThemeCandidatesを追加。
内部診断用projectThemeStatsはJsonIgnoreでLLMへ送らない。
LLMにはconsolidation済み候補と必要な集約統計を渡し、grouping・alias判定・親子抑制・関連再計算をさせない。
出力schemaは従来どおり。一覧はdominantThemesからの完全一致選択で検証するため、
group採用後に個別childをworkThemesへ再追加した応答はfallbackとなる。
自由文章の任意の言い換えまで機械的に完全検出するものではなく、本文はpromptでも制約する。

時間帯の本文は作業1〜2件と非作業傾向1件まで。
「画面が見られました」「表示がありました」の反復を減らす。
実際に本文へ出す候補の観測秒数が時間帯観測の半分以上の場合にだけ「中心でした」とし、
少数の具体的候補で大量のgeneric作業を代表させるときは「も見られました」に留める。
SNSが65%以上ならSNSが多いことを明示する。達成・集中・生産性を断定しない。
冒頭の共通注意書き、AI支援の全体/傾向に一回だけの扱いは維持する。
Entertainmentの算定は変更しない。

## 比較

[設定なし/ありの比較全文](testdata/theme-consolidation-comparison.md) は同じPhase 3.8.3実装で
group設定だけを切り替えたfixture比較。旧バージョンの全文再実行とは区別する。
実ユーザーの昨日のデータと外部LLMは使用していない。

| fixture | raw候補 | canonical候補 | group候補 | 置換child | 除去topic | 最終テーマ 前→後 | 文字数 前→後 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 統合用8 segment | 7 | 6 | 2 | 4 | 1 | 5→3 | 342→300 |
| 既存72 segment | 7 | 5 | 1 | 3 | 0 | 5→3 | 625→567 |

raw候補は保存済みworkのraw project + categoryのdistinct件数。
canonical候補は品質審査後・top切り詰め前の件数。
置換child件数と除去topic件数は別の指標で、単純な「消した行数」ではない。
統合用fixtureでは「音声翻訳」＋livevingo/my-translatorの併記が、音声翻訳系の1候補になる。
canonical重複・generic-only件数はalias/品質審査により改善前から0なので、0→0と扱う。
前回Phase 3.8.2で記録した同じ72 segmentは638文字だった。今回の設定ありは567文字。
200 segmentでもJSON 16,000文字未満・出力2,400文字未満を検証する。

DEBUGでcanonical候補数・group候補数・置換child数・除去topic数・最終選択件数を記録する。
alias/group読込先とエントリ数のDEBUGも維持。通常ログへ候補名・生の画面情報を大量出力しない。

## 検証

追加SummaryThemeConsolidationTestは17ケース。
group/表示名、category支持量、top切り詰め前の統合、弱いgroup、
alias一貫性とmember数、時間帯scope、部分包含、無設定、
single-project表示、validation、atomic reload、weak association非復活、
writer失敗、サイズ・算定非影響、minor themeの過剰な「中心」表現を検証した。

TDD: 最初の4件中2件が旧実装で失敗 → 初期実装で新旧60件成功。
拡充したテスト1件は短すぎるprojectが既存の足切りにかかるfixtureだったため、
generic fallbackの支持は保ちながらweak topicを1回だけにして修正した。
さらに時間帯2枠で親groupが押し出されるケースをRedで確認し、
明示された包含topicの支持をscoreへ加えて修正した。
ログはtarget/theme-consolidation-*.log（非コミット）。

最終検証（2026-09-25）:

| 対象 | 結果 | target内ログ |
| --- | --- | --- |
| Java全体 | 2426件中2424成功、既存2件失敗、error/skipなし | theme-consolidation-java-final.log |
| Activity関連 | 419件成功（追加17件含む） | 同上 |
| Client | 42件成功 | theme-consolidation-client.log |
| 型検査・build | 成功 | theme-consolidation-build.log |
| Rust | 66件成功 | theme-consolidation-rust.log |
| E2E初回 | 10成功、2件page.goto timeout | theme-consolidation-e2e.log |
| E2E失敗分再実行 | desktop/mobile 2件とも成功 | theme-consolidation-e2e-retry.log |

Javaの失敗は既存WebBoundaryTest:
heartbeatHasNoSequenceAndDisconnectReleasesListenerAndTimer、
sendIOExceptionUnsubscribesAndDoesNotCancelRun。
SSE listener数の期待差で、今回の対象実装・テストは変更していない。
変更前からの再現記録は [Daily Summary検証記録](activity-daily-summary.md) を参照。
追加17件、Phase 3.8.2の関連品質17件、既存date構文・通常Timelineのwriter非呼出し等は成功した。

E2Eの失敗は text and events are visible inline in output order のdesktop/mobile。
page.gotoの30秒timeoutで、該当2件のみworkers=1で再実行して成功した。
初回失敗はflakyとして残し、全件初回成功とは扱わない。

実行はJDK25、Maven offline（.m2/repository）、npm test、npm run build、cargo test、
npm run test:e2e。Java全体のREI_DATA_DIRはtarget/theme-consolidation-test-dataに隔離。
時間帯2枠の改善後にJava全体を再実行した。Client/Rustの実装変更はない。
コミットIDは完了報告を参照。

## 残課題

- 運用のalias/group設定はユーザーが明示する必要がある。設定例を自動インストールしない。
- 未設定の意味的包含を推測しないため、対応する設定がなければTHEMEとPROJECTが残る場合がある。
- 部分包含時のscoreはtopic数の比率で按分する。時間は個別projectへ按分せず、安全な観測値を保持する。
- 実運用データ・外部LLMによる自然文品質は別途確認が必要。

## Phase 3.8.3 Consolidation Pipeline Fix

未設定による raw alias 残存と、GROUP 不採用時の PROJECT/THEME 包含抑制漏れを区別して修正。
stable groupIds を全日・時間帯の共通 consolidator で使用し、LLM へは最終候補を渡す。
numeric score の電話番号誤検知も修正した。原因・設定適用手順・fixture の段階別比較・全テスト結果は
[Consolidation Pipeline Fix](activity-consolidation-pipeline-fix.md) を参照。
