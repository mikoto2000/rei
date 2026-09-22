# Activity Capture / Timeline

Phase 1〜3: デスクトップを観測し、構造化した履歴を保存し、振り返る機能。
デフォルトは無効。採点・行動評価・お小言は行わない。

## Architecture

```text
Spring @Scheduled → bounded activityExecutor (1 worker, queue=0)
  → ActivityCapture → DesktopActivityObserver
      foreground exclusion → RobotScreenCapture (per display) → foreground recheck
  → ImageChange → ActivityExtractor / VisionActivityExtractor → ActivityOutputParser
  → ActivityRecord → SqliteActivityStore + SessionMergePolicy → ActivitySession
  → ActivityTimeline → ActivityCommand / ActivityTools
```

既存の `AwtRobotDriver` / `RobotScreenCapture` / `CapturedScreen` による
複数モニター取得、`LlmModelProvider` のモデル設定、Spring の scheduler/executor、
主 DataSource (`memory.db`)、Picocli `RootCommand`、Chat の Tool 登録を再利用する。
Win32 foreground 取得は `WindowsDesktopActivityObserver` に隔離している。
既存の Computer Use の UIA probe は入力欄の内容も取得するため、Activity では
タイトル・PID・プロセス名・ウィンドウ識別子だけを取得する専用の read-only probe を使う。

専用 executor の最大並列数は1、キューは0。解析中の次回 tick は捨てる。
Chat や共通 scheduler 上で画像処理・ネットワーク待ちを行わない。
初回実行は設定間隔後。画面キャプチャもモデルの解決も起動時には実行しない。
停止時には executor を停止し、ActivityCapture の世代を無効化する。

## 設定

既存の外部 `application.yaml` に追記する。設定プレフィックスは `rei.activity`。
以下はデフォルト値（zone は実行環境のタイムゾーン）。

```yaml
rei:
  activity:
    enabled: false
    extraction-enabled: true
    capture-interval-seconds: 60
    screenshot-retention-days: 3
    change-threshold: 0.03
    session-gap-seconds: 90
    zone: Asia/Tokyo
    excluded-processes:
      - KeePassXC.exe
      - 1Password.exe
    excluded-window-title-patterns:
      - '*Password*'
      - '*Private Browsing*'
      - '*InPrivate*'
  llm:
    features:
      activity:
        model: your-vision-model
        # base-url / api-key などは既存の feature 設定と同じ
```

`enabled: true` が収集の明示的な有効化。
Windowsの対話デスクトップで起動し、既存Computer Useと同様にJVMへ
`-Djava.awt.headless=false` を渡す。Spring Bootの既定headless状態や非対話サービスでは取得できない。

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Djava.awt.headless=false'
```

`extraction-enabled: false` にすれば Phase 1 のみ動作し、Vision を呼ばず OS evidence を残す。
有効化後、解析が有効ならスクリーンショットと foreground 情報を設定した LLM サーバーに送信する。
`rei.llm.features.activity` が未設定の場合は既存のデフォルトモデル設定を使用する。
Vision と JSON Schema structured output に対応したモデルを設定すること。
Activity専用サーバーが設定されている場合、障害時にもデフォルトサーバーへ画像をフォールバック送信しない。

間隔は1秒以上、retention は0日以上、差分閾値は0〜1、session gap は0秒以上。
retention=0 は画像をディスクに保存しない。解析が有効ならメモリ上の画像を Vision に送る。
設定変更は再起動で適用される。1秒などの短い間隔は画像取得・API費用・DB更新量が増えるため、通常は60秒から調整する。

## Capture flow / Privacy

1. Retention を処理する。無効化・pause 中でも過去画像の期限は守る。
2. enabled / pause / shutdown を確認。
3. OS foreground metadata を取得。不明・取得失敗・除外対象なら画像取得前にスキップ。
4. 各モニターを別画像として取得し、識別子・bounds・取得時刻を保持。
5. Foreground を再取得。取得中に切り替わった場合は保存・解析せず破棄。
6. 各画像を32×32のRGB特徴量に縮小し、正規化平均絶対差を算出。
   最大差が閾値以下、モニター構成とforegroundが同じ、時間gapが小さい場合は重複扱い。
   最後に解析した画像と比較するため、少しずつ蓄積する変化も検出できる。
7. 新規画面のみ構造化解析。Schema検証に通った結果だけを保存。
8. pause/resume の世代番号を再確認し、停止前の解析結果が後から保存されることを防止。

除外プロセスは大文字小文字と `.exe` の有無を無視する。
タイトルは大文字小文字を無視した glob（`*` / `?`）の全体一致。
任意部分一致には `*文字列*` と指定する。除外リストを設定すると既定リストを置き換える。

foreground 以外のモニターや背面ウィンドウに秘密情報が表示されるケースを、
foreground 除外だけで完全には防げない。機密画面を扱う前に pause を使う。
画像取得中の瞬間的な切り替えをOS照合だけで完全に検出することもできない。
保存データはOSユーザーのデータディレクトリに置くが、独自の暗号化は行わない。
取得時の一時 foreground JSON は finally で削除する。

画像取得、Vision、不正出力、DB、retention の例外は Activity 内で隔離。
ログには失敗ステージと例外型だけを残し、画像・タイトル・モデル出力は記録しない。
LLM の通常 lifecycle events と Tool events は既存 API を使用する。
独自の Activity Event taxonomy は追加していない。

## ActivityRecord

主データは `activity_records` テーブルに保持する。

| フィールド | 意味 |
|---|---|
| id / capturedAt | UUID / UTC Instant |
| durationEstimate | 設定された観測間隔（秒）。操作時間の実測ではない |
| observations[] | モニターID、bounds、capturedAt。OS/画像取得から得た evidence |
| foreground | OSの processName / processId / windowTitle / windowId |
| inference.summary | 不確実性を含む画面の説明 |
| inference.activities[] | monitor、自由文字列の type、application、service、contentTitle、projectCandidate |
| confidence | 0〜1。モデル推論のconfidence |
| screenshotReferences[] | observations と同じ画像順の相対参照。期限切れ・保存なしを許容 |
| changeAmount / duplicate | 画像差分の大きさ / 重複判定 |
| continuityId | pause、除外、失敗、再起動を越えて継続を誤認しないための識別子 |

**Observation ≠ Inference**: Vision が読んだサービス名・コンテンツ名も推論側に置く。
モデルが OS observations を上書きすることはできない。Schema は未知のキー、
範囲外の confidence、存在しない monitor、不正JSON、末尾JSONなどを拒否する。
複数 monitor の複数 activities を保持し、category を固定 enum にしない。

重複時も軽量レコードを追加する。画像保存・Vision 呼び出しは行わず、直前の推論・
画像参照を引き継ぐ。`duplicate=true` によって新しい推論ではないことを識別できる。
静止画面を idle と断定しない。サンプリング間の操作、キーボード/マウスの実使用、
離席時間は今回測定していない。

## ActivitySession / Persistence

`activity_sessions` はレコードから生成する永続化された日次 projection。

| フィールド | 意味 |
|---|---|
| id | セッション先頭の record ID |
| startedAt / endedAt | 観測区間の始点・終点（UTC） |
| observedSeconds | 重複区間を除いた推定秒数。gapは加算しない |
| recordIds[] | 全レコードへの参照。元の推論・confidenceを追跡できる |
| inference | 代表推論と複数Activity candidates |
| primaryApplication | foreground process name |
| confidence | 構成レコードの最小confidence |

同じcontinuity、foreground、同じActivity candidatesの集合で、観測間隔が
session-gap以下の場合に結合する。summaryの言い回しやActivity順の変化だけでは分割しない。
空のcandidatesはsummaryも一致するときだけ結合する。
異なるサービス/コンテンツ/プロジェクト、foreground変更、大きなgap、日付境界では分割する。
次の観測が推定区間の終端より早い場合は、直前区間を次の観測時刻で切り詰める。
日付境界は設定zoneの深夜。日末の推定区間は深夜でクリップし、
翌日の観測がない時刻までセッションを延長しない。元のdurationEstimateはRecordに残る。

Record追加と、その日のSession再構築を1トランザクションで行う。
UTC epoch millisecondsに時間インデックスを持ち、日単位でレコードを読み直すので、
全履歴をメモリにロードしない。画像は `<rei-data-dir>/activity/screenshots/` に分離。
画像の削除にDBへのカスケードはなく、RecordとSessionは無期限で残る。
画像名に保存したcapturedAtでretention判定し、シンボリックリンクは辿らない。
DB保存失敗後の孤立画像もretention対象になる。

## Query / Slash commands / Summary

```text
/activity today
/activity yesterday
/activity summary
/activity 2026-09-22
/activity pause
/activity resume
```

`summary` は今日のセッションを深夜・午前・午後・夜にまとめる。
文章化は `ActivityTimeline.summary` の決定的なformatterで行い、追加LLMは不要。
記録がない場合は「記録なし」と返し、何もしていなかったとは断定しない。
pause はメモリ上の状態であり、再起動時には enabled 設定に従う。
resume は `enabled=false` を上書きしない。

`findByDate` は設定タイムゾーンの一日（DSTを考慮）。
`findBetween(startInclusive,endExclusive)` は半開区間で重なるSessionを返し、
境界を指定時間にクリップする。最大31日。Record IDsは元のSessionの参照を保持する。

通常のChatには `activityTimeline(date)` と `activityBetween(startInclusive,endExclusive)` を登録。
「昨日の夜なにしてた？」等は既存Agentが日時コンテキストを参照してToolを呼び出す。
後者はオフセット付きISO-8601日時を受け付ける。自然言語の固定パターン表は追加しない。
専用Timeline画面やクライアント独自Slash parserは今回追加していない。

## Tests / TDD

外部Desktop/Vision/ScreenshotStore/ActivityStoreはPortとして差し替える。
ポリシー→取得/解析→保存/検索の順にテストを先に追加し、コンパイル失敗のRedから実装した。
summaryの言い回しによる過分割、pause/resumeを越えた誤結合についても、
失敗する回帰テストを確認してから修正した。

`ActivityPolicyTest`、`ActivityCaptureTest`、`ActivityExtractionTest`、
`ActivityTimelineTest`、`ActivityIntegrationTest` が新機能を検証する。
実機画面のキャプチャと実際の外部Vision API呼び出しは自動テストでは実行しない。

## 将来 Phase 3.5〜5

Recordを失わず、時刻・推定duration・foreground・モニター・変化量・並行Activity・
project/content/service候補・confidence・継続境界を保持しているため、
context/project switch、セッション長、primary activity/projectなどを再集計できる。
focus/idle/entertainment minutesを作る場合も、今のデータだけで実測集中時間と断定せず、
新しいevidenceやユーザー定義を追加する。サンプリング間の細かな切り替えは復元できない。

高頻度化時は日次projection再構築を差分更新へ変更し、schema migration、
集計バージョン、日次summaryキャッシュを加えられる。
Phase 3.5以降のBehavior Evaluation、Project/Task/Working Set深い統合、
週次/月次Analytics、2〜6のProductivity Score、Adaptive Coachingは意図的に未実装。
