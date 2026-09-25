# Activity Timeline Phase 3.8.1 — Theme Enrichment

既存 `feature/activity-daily-summary` 上で Phase 3.8 の日次 summary のみを拡張する。
出力 schema と日付構文、Timeline、Behavior、分類器、EntertainmentDisposition の判定は変更しない。
Screenshot、Vision、Activity Extraction の再実行、保存データの更新、外部 LLM への実データ送信は行っていない。

## 集約と責務

- `DailySummaryAggregator`: 従来どおり重複観測を除き、日付と時間帯境界で観測秒数を分割する。
  categorySeconds と entertainmentSeconds は維持。テーマ順位は別集約であり、秒数を加算し直さない。
- `WorkThemeAggregation`: 主活動が development / research / documentation で、
  ENTERTAINMENT でない観測から候補を作る。補助表示を主作業へ昇格させない。
- project は既存 `ProjectNameNormalizer` の alias 正規化を先に適用する。
  同じ canonical project のカテゴリを最大3件、topic を最大2件に集約する。
  `sensevoice` 等の別名はユーザー設定に存在する場合だけ統合する。
  `livevingo`、`livetrans`、`palabra` は別 project のまま。Project Family / theme group は導入しない。
- topic は保存済み primary.contentTitle（content candidate）と、
  activity が1件だけの保存済み inference.summary から取得する。
  複数画面の要約を一つの project に誤帰属させない。content confidence が保存されていて0.5未満なら title を使わない。
  認識語彙は Activity分類、音声入力、文字起こし・音声認識、音声翻訳、翻訳、
  Visionモデル・画像認識、フィード要約の明示語（対応する英語を含む）。
  未対応 topic は project + category に戻す。任意の本文やコード名を LLM 入力へ転記しない。
- service は既存の意味分類と AI支援の表示量の集約にのみ使う。
  project名やクラス名から用途を推測しない。`AgentEventFactory` を API 設計と解釈しない。
  実装識別子の既存除外に `build_and_chat` と `software_development` を追加。
  明示的なユーザー alias による override は従来どおり可能。

## 候補の選択

日全体と時間帯で同じ処理を独立して行う。

1. project があれば project をキーに統合。なければ明示 topic、最後に category をキーにする。
2. 有効観測秒数が `min(全作業秒数, max(120, min(300, 全作業秒数 × 0.02)))` 未満の候補は除く。
   短い記録日の候補を全部消さず、通常の日の一瞬だけの project を主要テーマにしない。
3. topic 自体も `min(120, project観測秒数 × 0.2)` 以上を条件とする。
4. specificity は project+topic=3、project+category=2、topicのみ=1、categoryのみ=0。
5. score は
   `(観測秒数 + min(観測秒数×0.2, 観測回数×30) + min(観測秒数×0.2, 最長連続観測秒数×0.2)) × (1+specificity×0.25)`。
   連続性は同じ continuityId、観測間隔120秒以内。空白時間を観測秒数へ加算しない。
   同点は label 順。foreground の判定には既存 ActivityRolePolicy の confidence を使う。
6. 足切りを通った具体的候補があれば generic-only 候補は一覧から除く。
   具体的候補がなければ開発・調査等へ fallback する。件数を埋めるために作業を作らない。

`ProjectThemeStat` は canonicalProject、categories、themeCandidates、observedSeconds、
observationCount、longestContinuousSeconds、specificity、score、label を持つ。全体は最大5件。
`DailySummaryAggregate` に projectThemeStats と significantAiAssistance を追加。
各 Bucket は排他的 categorySeconds、dominantProjects、workThemes（各最大3件）、
secondaryThemes（非作業カテゴリ最大1件）を持つ。全体の作業一覧は最大5件、非作業一覧は最大3件のまま。

## 文章生成

`DailySummary` fallback も `LlmDailySummaryWriter` も順位付き候補を利用する。
LLM の出力 schema は維持し、workThemes は候補からの完全一致選択を検証する。
入力は引き続き16,000文字以下。LLM にランキングや alias 判定を委ねない。
prompt に入力にない project/topic の生成禁止、固有テーマ優先、隣接時間帯の定型文反復禁止を追加する。

fallback は全体に上位1〜2件、時間帯にその時間帯の1〜2件を含める。
一番多い非作業カテゴリが時間帯観測の65%以上なら「SNS閲覧の観測が多い一方、rei…」のように
実際の観測比率も残す。単にSNSが少し多いことを理由に作業テーマを消さない。
文字数を保つため長いテーマ2件が130文字を超える場合は1件に絞る。
傾向は従来の切り替え・長い区間等を使い、project 一覧を重ねない。

AI支援を時間帯の secondary/background 候補から除く。
一日で600秒以上かつ全観測の20%以上の表示があった場合だけ significantAiAssistance を立て、
全体または傾向に一度言及する。fallback は傾向に一度だけ追加する。
出力検証でも時間帯の「AI支援」と全体/傾向の同語反復を拒否する。
任意の同義語まで機械判定するものではなく、その他は prompt による制約。
SNS 等の実際に異なる時間帯テーマを表現し、根拠が同じ時間帯を無理に違う内容へ言い換えない。

## 比較と検証

[改善前後の全文](testdata/theme-enrichment-comparison.md) は固定 fixture による deterministic fallback の比較。
実ユーザーの昨日の記録や外部 LLM による文章品質の検証ではない。

| fixture | 作業テーマ数 前→後 | genericのみ 前→後 | 同一時間帯文の余剰件数 前→後 | 出力文字数 前→後 |
| --- | --- | --- | --- | --- |
| 既存72 segment | 5→5 | 0→0 | 2→2 | 508→655 |
| SNS優勢・時間帯別project | 4→4 | 0→0 | 3→0 | 357→512 |

後者は各時間帯に SNS 5時間と作業30分、残り30分は未観測。
前者は元々具体的テーマのみで、同じ配置が2回繰り返されるため generic 件数・反復件数は変わらない。
generic抑制は専用テスト（generic開発5時間＋rei開発4時間）が検証する。
一覧は「開発＋reiの開発」から「reiの開発」だけになる。
文字数は約29% / 43%増えるが、いずれも数百文字の短い出力で、2,400文字未満の既存上限を維持する。

追加 `ThemeEnrichmentTest` は11ケース。
generic抑制、alias/category統合、時間帯固有性とSNS優勢、AI反復、
topic根拠、projectなし、短時間候補抑制、観測回数と連続性、
識別子除外と非推測、LLM失敗fallback、unknown優勢時の候補保持・AI出力検証、比較出力を検証する（複数観点を1ケースで検証）。
最初の4ケースは比較ケース以外の3件が旧実装で失敗した。
追加テスト拡充時のコンパイル誤り2件は修正して再実行した。ログは target/theme-enrichment-*.log。

最終検証（2026-09-25）:

| 対象 | 結果 | target 内ログ |
| --- | --- | --- |
| Java 全体 | 2392件中2390成功、既存2件失敗、error/skipなし | theme-enrichment-java-verified.log |
| Activity関連 | 385 / 385 成功（追加11件含む） | 同上 |
| Client | 42 / 42 成功 | theme-enrichment-client.log |
| Client型検査・build | 成功 | theme-enrichment-build.log |
| Rust | 66 / 66 成功 | theme-enrichment-rust.log |
| E2E | desktop/mobile 12 / 12 初回成功、timeoutなし | theme-enrichment-e2e.log |

Java の失敗は WebBoundaryTest の heartbeatHasNoSequenceAndDisconnectReleasesListenerAndTimer と
sendIOExceptionUnsubscribesAndDoesNotCancelRun。SSE listener 数の期待差で、Phase 3.8 と同じ2件。
以前の変更前再現記録は [Daily Summary 検証記録](activity-daily-summary.md) と
[Behavior 会話履歴](behavior-conversation-history.md) を参照。今回は該当テスト・SSE 実装を変更していない。

実行は JDK25 / Maven offline（repo local .m2/repository）、npm test、npm run build、
cargo test、npm run test:e2e。REI_DATA_DIR は target/theme-enrichment-test-data に隔離。
既存 Picocli integration tests の summary / today / yesterday / YYYY-MM-DD および
通常 Timeline の writer/append 非呼出しも全件成功。
外部 LLM の実リクエストではなく、既存 mock stream の schema / timeout / fallback を確認した。
最終レビューで unknown 優勢時の候補省略を修正し追加回帰テストを含めて Java 全体を再実行した。
コミット ID は完了報告を参照。

## 残課題

安全な限定語彙に含まれない topic は project/category 表示となる。
保存時点で project/topic が欠落していたり、別画面にしか出ていなければ主作業として復元しない。
新しい topic 語彙は保存例と回帰テストに基づいて追加する。
LLM の自由文章内の未知固有名詞を完全に検出する仕組みはない。候補一覧は厳密検証し、
本文は制約付き prompt と既存の長さ・形式検証を適用する。
