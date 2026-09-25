# Phase 3.8.2 — Theme Attribution Quality

既存 `feature/activity-daily-summary` を継続。Daily Summary 側の派生モデルだけを変更する。
分類器・EntertainmentDisposition・Behavior・Timeline 一覧・日付構文は変更しない。
保存済み metadata を読み、Screenshot / Vision / Activity Extraction を再実行しない。
関連を永続化したり、LLM に alias や関係を学習・保存させたりしない。

## Project candidate と canonicalization

`ProjectNameNormalizer.project(Activity)` が raw candidate の入口。
明示 alias を最優先し、それ以外は以下を拒否する。

- project / repository / repo / workspace / software / coding / source / src / main / app / application / code
- 既存の category名、一般的なアプリ・サービス名、実装識別子
- 2文字未満、60文字超、不正形式、当該activityの application / service と同一の名前

`rei` など短い実名を残す。明示 alias でユーザーが指定した project はこの推測的な品質フィルタを上書きできる。
保存済み project confidence が0.5未満なら既存同様 project を採用しない。
first activity と異なる primary の場合は、その activity の secondaryConfidence を参照し、
他の activity の高い field confidence を流用しない。対応が不明なら最大0.5とする。

alias ファイル → ProjectAliasStore → ProjectNameNormalizer → DailySummaryAggregator の入口で
canonical project を確定し、project統計、block、WorkThemeAggregation、Bucket、fallback、LLM 入力へ再利用する。
raw project を後段で復元する経路は確認されなかった。
同一 canonical project のカテゴリは十分な秒数がある上位3件までに統合する。

### sensevoice の重複調査

Phase 3.8.1 は alias を自動搭載しない設計だった。未設定なら sensevoice と sensevoice-input は別名になる。
この環境では REI_DATA_DIR 環境変数がなく、既定の
`%LOCALAPPDATA%/Rei/activity/project-aliases.yaml` も存在しなかった。
実行中アプリの独自 data-dir / 設定 override までは確認していないため、提示された出力の原因と断定はしない。

設定済み alias の適用漏れは fixture では再現しなかった。
今回、ファイルから writer 入力・fallback まで canonical 名が維持される統合テストを追加した。
DEBUG に読み込み先・ファイル有無・canonical/aliasエントリ数と canonicalization hit 数を出す。
運用の alias ファイルは自動作成・変更しない。設定する場合の内容は
[alias設定例](testdata/project-aliases.example.yaml) を参照。
名称類似や誤字推測による自動統合、Project Family / theme group は導入しない。

## ProjectThemeAssociation

新しい derived record は以下を持つ。

- canonicalProject / theme
- supportingObservationCount / supportingDuration（秒）
- foregroundObservationCount / supportingSessionCount / longestContinuousSeconds
- projectConfidence / themeConfidence / associationConfidence
- evidenceSources / provenance

provenance は保存済み ActivityRecord.id、monitor、continuityId、クリップ済み start/end、
候補の由来（SAME_ACTIVITY_TITLE / SINGLE_ACTIVITY_SUMMARY）、foreground の裏付け、個別confidence、
既存 ActivityEvidenceDisplayFormatter.Source の出典を保持する。
ここで observation は ActivityRecord 内の選択された activity。物理スクリーンショットの枚数は support として数えない。
タイトル本文や画像は保持しない。provenance と内部診断用の個別confidence・foreground回数・session数・連続秒数・出典は Jackson の JsonIgnore で LLM に送らない。
LLM入力には canonicalProject、theme、supportingObservationCount、supportingDuration、associationConfidence の5項目だけを渡す。
長いproject名を含めて関連リストが増えても、本文生成に必要な入力を小さく保つ。

同じ選択 activity の project と topic を対として採取してから、
continuityId のセッション、日次、時間帯へ集約する。
Session / SummarySegment / Bucket の project配列と theme配列を cross join しない。
入力segmentで重複する record は既存の除重処理で一度だけ数える。
観測時間は区間をクリップした秒数であり、未観測時間を足さない。
連続性は同じ continuityId、間隔120秒以内の観測秒数の合計。セッション数自体に加点しない。

## Theme の品質と foreground

SPECIFIC 相当は前段の明示語彙のみ。speech-to-text / transcription / 文字起こしを
文字起こしへ正規化する。GENERIC 相当の開発・調査・文書作業は category fallback。
build_and_chat / software_development / coding 等の NOISY な本文から独自テーマを作らない。

タイトル由来と単一activityのレコード要約由来を区別する。
後者は record confidence の半分を theme confidence とし、回数や時間が多くても project と確定結合しない。
複数activityのレコード要約は引き続き候補に使わない。

foreground の基礎判定は既存 RolePolicy の選択結果を使う。
BACKGROUND_VISION の利用、または保存された VISION_BACKGROUND source がある観測では、
foreground window title に同じ project と contentTitle が明示される場合だけ foreground支持とする。
単語境界で確認し、例えば freight の一部分を rei と見なさない。
混在レコードの診断は field単位の provenance ではないため、この場合は保守的に扱う。
出典は既存 WINDOW_METADATA / FOREGROUND_VISION / BACKGROUND_VISION を再利用する。
レガシー診断から USED を推測しない。source不明でも observation ID と候補由来は保持する。

## Confidence と採用ルール

project / theme confidence は支持秒数で加重平均する。
association confidence は独立に以下を計算する（校正済み確率ではなく summary の選択用スコア）。

```text
0.35 × 同一activityタイトル由来の支持秒数割合
+ 0.15 × foreground支持秒数割合
+ 0.15 × projectConfidence
+ 0.15 × themeConfidence
+ 0.10 × min(1, supportingObservationCount / 3)
+ 0.05 × min(1, supportingDuration / 300)
+ 0.05 × min(1, longestContinuousSeconds / 300)
```

- 直接支持または foreground 支持が80%未満なら最大0.44。
- 観測3回未満かつ300秒未満、または project/theme confidence のどちらかが0.75未満なら最大0.69。
- 0.75以上だけ strong association として specific theme を採用する。
- 弱ければ canonical project + 保存済みcategoryへ戻す。project がなければ strong theme only、
  それもなければ generic categoryへ戻す。

3回の短い直接観測、または1回でも5分以上の直接観測を支持の目安とする。
backgroundや要約由来の支持を大量に足して、直接支持の欠落を補わせない。
same session / same bucket のみの組み合わせは候補を作らないため confidence 自体を付けない。
全体・時間帯は同じ実装をそれぞれの観測範囲で使うので、日全体で強くても時間帯内で支持不足なら弱い表現になる。

既存のテーマ順位（時間・回数・連続性・具体性）は維持するが、
specificity 加点の前に association を審査する。細かいテーマほど無条件に優先するわけではない。
カテゴリも project観測秒数の20%または120秒（短い方）以上の支持があるものに絞る。

## LLM と fallback

DailySummaryAggregate.ProjectThemeStat と Bucket に strongAssociations を追加。
LLM へは強い関連の project/theme/confidence/support と、弱い場合の安全な fallback label を渡す。
弱い候補そのものは LLM 入力に含めず、LLM に再結合・confidence再計算をさせない。
出力 schema は変更せず、一覧は許可された label の完全一致で検証する。
入力は16,000文字以下、作業一覧5件以下、非作業一覧3件以下を維持する。

fallback も同じ選別済み label のみを使う。writer失敗でも弱いテーマを復活させない。
時間帯の「作業テーマとして見られました」「補助表示」を除き、
「に関する画面が見られました」「も一部で表示されていました」等へ簡潔にする。
操作・集中・成果は断定しない。AI支援は従来どおり全体/傾向に最大1回。

DEBUG は strong / weak 関連数、rejectされたproject候補数、canonicalization hit数を記録する。
reject数はgenericだけでなくconfidence不足等も含む。本文・生の要約・画像はログ出力しない。

## Before / After と検証

[比較全文](testdata/theme-attribution-comparison.md) は外部LLMを使わない固定fixtureのfallback。

| fixture | generic project 前→後 | canonical重複 前→後 | 弱いrei-topic 前→後 | 文字数 前→後 |
| --- | --- | --- | --- | --- |
| 品質fixture | 1→0 | 0→0 | 0→0 | 345→329 |
| 既存72 segment | 0→0 | 0→0 | 0→0 | 655→638 |
| SNS優勢・時間帯別project | 0→0 | 0→0 | 0→0 | 512→512 |

alias設定済みfixtureなのでcanonical重複は改善前から0。
品質fixtureでも弱い1分topicが他の観測に対して短いため、旧実装の足切りで既に省略される。
一方、単独の rei + transcription 60秒の回帰テストでは旧実装が specific theme を出して失敗し、
新実装は rei の開発になる（弱い関連1→0）。要約だけ1800秒の場合も同様に修正した。
十分な支持がある livevingo の翻訳・文字起こしは残る。
実ユーザーの昨日のデータや外部 LLM への送信は今回実施していない。

新規 ThemeAttributionTest は17ケース。
generic候補、alias/境界、弱い/強い関連、要約のみ、cross product防止、foreground/background、
provenance、support回数/秒数/連続性、confidence、theme only、自然化、サイズ、writer失敗を検証。
既存 ThemeEnrichmentTest の要約由来theme期待値は、今回の安全なgeneric fallbackへ更新した。
TDD Red は6件中4件失敗。Green/Refactorで新旧55件成功後、
部分文字列をprojectとしない17番目のテストが正規表現のescape過多を検出し修正した。

検証結果（2026-09-25）:

| 対象 | 結果 | target 内ログ |
| --- | --- | --- |
| Java 全体・再実行 | 2409件中2406成功、3件失敗、error/skipなし | theme-attribution-java-final.log |
| Activity関連 | 402件成功（新規17件含む） | 同上 |
| 最終入力圧縮後のDaily Summary回帰 | 56件成功 | theme-attribution-final-focused.log |
| Client | 42件成功 | theme-attribution-client.log |
| Client型検査・build | 成功 | theme-attribution-build.log |
| Rust | 66件成功 | theme-attribution-rust.log |
| E2E | desktop/mobile 12件初回成功、timeoutなし | theme-attribution-e2e.log |

Java 全体の失敗:
- WebBoundaryTest.heartbeatHasNoSequenceAndDisconnectReleasesListenerAndTimer
- WebBoundaryTest.sendIOExceptionUnsubscribesAndDoesNotCancelRun
- ExternalAgentProcessRunnerTest.capturesBothStreamsAndNonZeroExit（FAILED期待に対しINACTIVITY_TIMEOUT）

Web 2件は Phase 3.8 / 3.8.1 と同じ SSE listener 数の期待差。
以前の変更前再現記録は [Daily Summary](activity-daily-summary.md) を参照。今回は対象実装・テストを変更していない。
外部プロセスの1件は初回全体では成功、2回目全体でtimeoutとなった。単独再実行は1件成功（theme-attribution-external-retry.log）。flakyなtimeoutとして記録し、全体初回成功扱いにはしない。
初回全体では Web 2件に加え、新規の部分文字列テストが失敗したため、正規表現を修正している。

全体テスト後、LLMへ送る関連情報を5項目に絞った。60文字project名の上限ケースを含む
最終56ケースが成功。provenance非送信と16,000文字未満を検証した。
通常日次の各構文、Timelineでのwriter非呼出し、store非書込み、writer失敗fallbackの既存integrationも成功。
JDK25 / Maven offline（.m2/repository）、npm test、npm run build、cargo test、
npm run test:e2eを実行。全体の REI_DATA_DIR は target/theme-attribution-test-data に隔離した。

## 残課題

- 運用でsensevoice等を統合するには明示alias設定が必要。独自 data-dir を使う場合は読み込み先の確認が必要。
- 診断にfield単位の出典が無い混在観測では、正しいテーマでも保守的にfallbackし得る。
- 限定語彙外のテーマはcategoryへ戻す。勝手な意味補完はしない。
- LLM自由文章内の任意の言い換え・新固有名詞を完全に機械検出するものではない。
  一覧は厳密検証、本文はstructured入力とpromptで制約する。実運用の文章品質評価は別途必要。
