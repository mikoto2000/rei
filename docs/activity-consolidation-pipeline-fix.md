# Phase 3.8.3 Consolidation Pipeline Fix

作業ブランチ: `feature/activity-daily-summary`。
2026-09-25、開始時は変更なし。追加コミットのIDは完了報告を参照。

## 原因と確認できた範囲

1. **alias/group 未設定**: 確認した既定の
   `%LOCALAPPDATA%/Rei/activity/project-aliases.yaml` は存在しなかった。
   `ProjectAliasStore.snapshot()` はこの場合、空の alias/group を返す。
   したがって `sensevoice` は独立した正当な project として残る。
   設定済み alias を時間帯だけ適用し忘れる経路は見つからなかった。
   稼働プロセスの JVM property / 環境変数 / summary property は未確認のため、
   **実運用も必ずこのファイルを参照した、とは断定しない**。
2. **設定済みでも抑制が不足**: consolidator は採用された GROUP だけを
   topic の包含元としていた。単一 project、支持時間・coverage が不足する group では
   PROJECT を残す設計だが、この場合の明示 group membership を包含判定に使っていなかった。
   fixture では設定を実際にロードしても
   `文字起こし・音声翻訳` と `livevingo の翻訳` が最終候補に同居した。
3. **LLM 呼出し前の誤検知**: JSON 全体を機密情報検査に渡していたため、
   score `3610.5218750000004` の数字列が `PHONE_JP` に一致。
   6,657文字の入力でサイズ上限ではないのに writer が例外となり、モデル未呼出しで
   fallback へ進んでいた。fixture の実際の ChatModel 境界テストで発見した。
   ユーザー提示の実出力が同じ理由で fallback したかは runtime log 未確認。

`文字起こし・音声翻訳` は LLM の創作でも設定 group の displayName でもない。
`WorkThemeAggregation` が project 未確定の強い topic 関連を連結した **THEME** 候補。
テーマは固定 topic 辞書に由来し、包含関係は設定の `themes` と `projects` からのみ求める。

## 実際の経路と変更

| 段階 | 確認結果 / 修正 |
| --- | --- |
| ActivityConfiguration | ProjectAliasStore を DailySummaryService に渡し、その service を ActivityTimeline に注入。group 設定を捨てる別経路なし |
| ActivityRecord / SummarySegment | 保存済み raw evidence は変更しない。スクリーンショット・Vision・Extraction を実行しない |
| DailySummaryAggregator | primary project を `ProjectNameNormalizer.project(primary)` で一度正規化。全日、bucket、project統計、block、association には canonical を渡す |
| ProjectAliasStore | alias と group を原子的に読み込み、次回 summary 時に hot reload。group の project key も同じ normalizer を通す |
| WorkThemeAggregation | 同一 project/theme の evidence を評価。raw title/project は保存 evidence の foreground 照合にのみ使用し、候補の identity や LLM 入力にはしない |
| SummaryThemeGroups | `idsFor(project, topics)` で明示 membership から stable group ID を得る。単語の部分一致で包含推定しない |
| SummaryThemeCandidate | `groupIds` を追加。PROJECT は親group ID、THEME は構成topicが属するgroup ID、GROUP は自身のIDを保持 |
| DailySummaryThemeConsolidator | 既存の group 支持条件・score 計算を維持。GROUP に加え採用 PROJECT の group ID も用い、THEME の包含された部分だけ抑制 |
| Final ranking | 仮選択→包含抑制→再順位付けを安定まで実行。抑制によって新たに選ばれる代表も反映。未採用 group/project は topic を消さない |
| LlmDailySummaryWriter | final candidates を渡す。統合前の `topProjects` と `majorWorkBlocks.theme` を送信から除外。block の時刻・秒数は保持 |
| 機密情報検査 | JSON の文字列値を再帰的に検査し、算術の numeric 値は対象外。電話番号を含む project 文字列は引き続き拒否 |
| LLM output | schema・最終テーマの許可リスト検証は維持。不正応答・例外は同じ集約の fallback へ |
| DailySummary / Formatter | 最終候補と検証済み文章だけを使用。raw evidence の参照・候補再追加はない |

GROUP 内の `memberProjects` は canonical な説明用 metadata として残す。
削除するのは抑制済みの独立した child **候補**と統合前の作業ラベルであり、membership 自体ではない。

全日（最大5件）・各時間帯（最大2件）は同じ consolidator を使用する。
scope の観測量が異なるため group 採否は異なり得るが、正規化と包含抑制の規則は共通。
包含抑制はモデル呼出し前に完了する。LLM に grouping を任せず、出力後の文字列置換もしない。
既存設計に validation retry はないため、今回も追加せず不正応答は fallback とする。

## 設定の適用

[今回のプロジェクトに絞った設定例](testdata/summary-theme-groups-2026-09-24.yaml) を追加した。
sensevoice 系4 alias、sensevoice-input / livevingo / DeepSeek の明示 group のみ。
単一 project でも表示group化するため `allowSingleProject: true` を指定。
同じgroupに他のprojectを自動追加しない。

**設定例は自動ではロードされない。** 次の優先順位で有効なデータディレクトリを確認する。

1. JVM `-Drei.data-dir=...`
2. 環境変数 `REI_DATA_DIR`
3. Windows 既定 `%LOCALAPPDATA%/Rei`

その配下の `rei.activity.summary.project-aliases-file`（既定
`activity/project-aliases.yaml`）に設定する。
既存ファイルがあれば alias/group を統合し、ファイルを上書きしない。
未作成の場合、既定ディレクトリを使っていると確認できた環境での例:

```powershell
$summaryConfig = Join-Path $env:LOCALAPPDATA 'Rei/activity/project-aliases.yaml'
if (Test-Path -LiteralPath $summaryConfig) {
  throw '既存設定に alias/group を統合してください'
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $summaryConfig) | Out-Null
Copy-Item -LiteralPath 'docs/testdata/summary-theme-groups-2026-09-24.yaml' -Destination $summaryConfig
```

次の `/activity summary yesterday` から反映される。無効な編集時は alias/group 両方の
last-good snapshot を保持。ファイル削除・空設定では Phase 3.8.2 相当へ戻り、
意味的な alias/group を暗黙に補完しない。

case・前後空白・空白/`_`/`-` の差は既存 normalizer で吸収。
`sensevoice`→`sensevoice-input` のように文字自体が異なるものは明示 alias が必要。

## DEBUG 診断

logger `dev.mikoto2000.rei.activity` を DEBUG にすると以下を確認できる。

- `[summary-theme] config`: 絶対設定パス、存在、alias数、effective group 定義
- `raw`: 最大60文字の project 候補（制御文字除去、title等は含まない）
- `canonical`: canonical project、aliasHit、groupIds
- `scope`: wholeDay / lateNight / morning / afternoon / evening
- `grouped`: 採用可能な group、置換した project
- `scored`: candidate の level / groupIds / label / score
- `suppressed`: 置換project、包含元group ID、抑制topic数
- `final`: LLM/fallback 共通の最終候補

通常 INFO には追加しない。設定ログは設定変更時に出る。
raw/canonical/候補ログは project 名を含むため、診断時のみ有効化する。

## 2026-09-24 相当 fixture

`ConsolidationPipelineTest.fixture()` は保存済み相当の6区間、計10,800秒。
深夜に sensevoice 文書作業と DeepSeek 開発、午前に sensevoice-input 文書作業と rei、
夜に project 未確定の transcription / speech translation と livevingo translation。
個人の実DBのコピーではない。

| stage | 値 |
| --- | --- |
| raw | sensevoice、sensevoice-input、livevingo、rei、deepseek-vl-flash-vision-exp、project未確定 topic「文字起こし」「音声翻訳」 |
| canonical | sensevoice-input（2区間、3,600秒）、livevingo、rei、deepseek-vl-flash-vision-exp、THEME「文字起こし・音声翻訳」 |
| grouped（従来例） | speech-input / speech-translation membership は見つかるが単一projectなので GROUP は不採用 |
| suppressed（修正後・従来例） | 全日は sensevoice-input と livevingo の group membership が両topicを包含。夜は livevingo が音声翻訳のみ包含し、文字起こしは残す |
| final（従来例） | sensevoice-input の文書作業、livevingo の翻訳、rei の開発、deepseek-vl-flash-vision-exp の開発 |
| grouped（今回例） | speech-input / speech-translation を GROUP 化。DeepSeek は全日coverage不足、深夜では GROUP 化 |
| suppressed（今回例） | sensevoice-input / livevingo の個別候補、および全日の両topic |
| final（今回例） | 音声入力・文字起こし系の文書作業、音声翻訳系の開発、rei の開発、deepseek-vl-flash-vision-exp の開発 |

修正前コードに設定を与えても複合THEMEが残ることを Red で確認した。
以下の文字数・重複比較は **未設定→設定適用＋修正** の比較で、設定差も含む。
重複数は主な作業テーマ一覧の alias重複ペア / 明示groupで包含されるTHEME-PROJECTペア。

| 指標 | 未設定 | 修正＋従来例 | 修正＋今回例 |
| --- | ---: | ---: | ---: |
| sensevoice alias 重複ペア | 1 | 0 | 0 |
| livevingo / 音声翻訳 親子重複ペア | 1 | 0 | 0 |
| 主テーマ数 | 5 | 4 | 4 |
| formatter 出力文字数 | 454 | 470 | 421 |

従来例で16文字増えるのは空いた枠へ DeepSeek が入るため。今回例では33文字減。
全日の raw alias と深夜の raw alias が消えること、
夜の未対応「文字起こし」を誤って消さないことを確認した。
LLM 成功テストは mock が有効な structured output を返すもので、外部LLMを呼んではいない。

## テストと実動作確認

追加8件（parameterizedの3ケースを含む）。
missing config / hot reload、case・separator、単一project包含抑制、
実 ChatModel UserMessage のフィールド単位検査、GROUP 採用時の child候補不在、
成功・invalid output・例外 fallback、numeric誤検知・実電話文字列拒否、
固定時計での `summary yesterday` コマンドを検証。

最初にコンパイル上のDATE import衝突を修正後、5件中4件失敗を確認した。
内訳は親子重複1件と、LLM前の機密誤検知による model 未呼出し3件。
修正後は既存の consolidation / writer テストを含め31件成功。
追加拡充時の expected list に低coverage DeepSeek の残存を反映した後、追加8件すべて成功。

実運用アプリでの `/activity summary yesterday` は未実行。
固定時計＋保存record相当fixtureを使う実コマンド経路では成功し、store.append 非呼出しも検証。
運用設定・保存DB・外部LLMへの変更や送信は行っていない。

全体の検証結果（2026-09-25、JDK25、Maven offline）:

| 対象 | 結果 | target 内ログ |
| --- | --- | --- |
| Java 全体 | 2,434件、2,432成功、2失敗、error/skipなし | pipeline-java-all.log |
| Activity 関連 | 427件すべて成功（追加8件含む） | 同上 |
| Client | 42件すべて成功 | pipeline-client.log |
| 型検査・build | 成功 | pipeline-client-build.log |
| Rust | 66件すべて成功 | pipeline-rust.log |
| E2E 初回 | 10成功、2件 page.goto 30秒 timeout | pipeline-e2e.log |
| E2E 失敗分再実行 | 2件とも成功（workers=1） | pipeline-e2e-retry.log |

Java の失敗は前回記録と同じ WebBoundaryTest の
`heartbeatHasNoSequenceAndDisconnectReleasesListenerAndTimer`（listener 2、期待1）と
`sendIOExceptionUnsubscribesAndDoesNotCancelRun`（listener 1、期待0）。
対象の SSE 実装・テストは変更していない。今回も全件成功とは扱わない。
既存の変更前再現記録は [Daily Summary](activity-daily-summary.md) を参照。

E2E の初回失敗は desktop/mobile の
`text and events are visible inline in output order`。
該当2件のみ再実行して成功したが、初回 timeout は flaky として記録する。
Java の REI_DATA_DIR は `target/pipeline-test-data` に隔離。
ログ・比較生成物は `target/pipeline-*.log`、
`target/consolidation-pipeline-comparison.txt`、
`target/pipeline-single-project-example.txt`（非コミット）。

分類、EntertainmentDisposition、Behavior、通常Timeline、日付構文に変更なし。
残課題は運用側 effective config の適用確認、および実データ・外部LLM文章での再確認。
