# Activity Capture / Timeline

Phase 1〜3: デスクトップを観測し、構造化した履歴を保存し、振り返る機能。
デフォルトは無効。採点・行動評価・お小言は行わない。

## Architecture

```text
Spring @Scheduled → bounded activityExecutor (1 worker, queue=0)
  → ActivityCapture → DesktopActivityObserver
      foreground exclusion → RobotScreenCapture (per display) → foreground recheck
  → ImageChange (RAM) → ActivityExtractor / VisionActivityExtractor (RAM) → ActivityOutputParser
  → ScreenshotPersistencePolicy → optional ScreenshotStore
  → ActivityRecord → SqliteActivityStore + SessionMergePolicy → ActivitySession
  → ActivityTimeline + raw evidence → ActivityRolePolicy → SemanticSessionPolicy
  → SummaryGroupingPolicy → SummarySegment → ActivitySummaryFormatter → today / date / ActivityTools
  → TrendSummaryPolicy → TrendSummarySegment → TrendSummaryFormatter → /activity summary
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
    keep-screenshots: false
    keep-on-extraction-failure: false
    capture-interval-seconds: 60
    vision-image-scale: 0.5
    screenshot-retention-days: 3
    change-threshold: 0.03
    session-gap-seconds: 90
    summary-normal-merge-gap-seconds: 120
    summary-maximum-merge-gap-seconds: 300
    summary-brief-switch-seconds: 120
    primary-confidence-threshold: 0.5
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

## Screenshot lifecycle / Memory-First

画像は `CapturedScreen` 内のモニター別 `DisplayCapture.image` (`BufferedImage`) として取得する。
`ImageChange` はRAM上で特徴量を計算する。重複画面はPNGエンコードもVision呼び出しも保存もせず、
画像への参照を解放する。継続を記録する軽量ActivityRecordは従来どおり追加する。

変更画面は送信専用コピーを `rei.activity.vision-image-scale`（既定0.5）で縦横それぞれ縮小し、`PngScreenshotEncoder` により
`BufferedImage → MemoryCacheImageOutputStream → ByteArrayOutputStream → byte[]` と変換する。
`VisionActivityExtractor` が `ByteArrayResource` / Spring AI `Media` に渡し、
既存OpenAI互換クライアントがJSONリクエスト中の `data:image/png;base64,...` にする。
画像用 `Path`、temporary PNG、multipartファイルは経由しない。
ImageIOのOutputStream便利メソッドは内部でディスクキャッシュを使用し得るため、
明示的な `MemoryCacheImageOutputStream` を使う。他機能に影響するグローバルな
`ImageIO.setUseCache` の変更は行わない。形式はPNGのまま。0.5なら1920×1080は960×540、画素数は約1/4になる。
小さい文字の認識精度と処理負荷のトレードオフがある。設定範囲は0超〜1以下、1.0で原寸送信へ戻せる。
端数は四捨五入し各辺最低1px。元画像・モニター座標・変化検出・任意の保存Evidenceは原寸を維持する。
縮小とPNG変換はRAM内だけで行う。これは入力画像の負荷軽減であり、出力上限超過や接続障害の解消を保証しない。

通常はVision成功後に構造化Recordだけを保存し、画像はRAMから解放する。
ScreenshotStoreはEvidenceを明示的に保持するときだけ呼ばれるoptional persistence。

| 判定結果 | 保存に必要な設定 | 保存しない場合 |
|---|---|---|
| 変更画像・解析成功（Phase 1のみの場合も含む） | `keep-screenshots: true` | Recordは保存、画像参照は空リスト |
| 変更画像・解析失敗（encode/request/構造検証の失敗） | `keep-on-extraction-failure: true` | Recordも画像も保存しない |
| 重複画像 | 設定にかかわらず新規保存なし | 直前の推論・既存参照を引き継ぐ |
| 除外、foreground切替、pause/停止 | 設定にかかわらず保存なし | 取得前にスキップ、またはRAM内で破棄 |

両設定のデフォルトは `false` で、独立して作用する。
`keep-screenshots: true` だけでは失敗画像を保存しない。
`keep-on-extraction-failure: true` だけでは成功画像を保存しない。
retention=0は両設定より優先され、画像を保存しない。
保存したい場合は対応する設定をtrueにしてretentionを正の値にする。
Evidence保存が失敗しても、正常な解析結果は画像参照なしでRecordとして残す。
解析失敗Evidenceには時刻・UUID・画面順序を含む既存のファイル名を使い、不正なRecordは作成しない。
pause/close中に終わった解析失敗もEvidence保存を行わない。

Retentionは成功・失敗のEvidenceに同じルールを適用する。
保持設定を後から無効にしても過去のEvidenceの期限処理を継続する。
新たな画像保存がなければEvidenceディレクトリも作成しない。

**SSD write suppressionの範囲:** デフォルトの画像ファイル書き込みと、
PNGエンコード時の隠れた画像一時ファイルをなくした。SQLiteの構造化Record/Session更新、
小さな既存Agent lifecycle events、既存Win32 probeの一時foreground JSONは残る。
従って「画像書き込み0」は「全種類のdisk I/Oが0」という意味ではない。
foreground JSONは画像やVision入力ではなく、OS metadata取得用の小さな一時ファイルで、finallyで削除する。

## Capture flow / Privacy

1. Retention を処理する。無効化・pause 中でも過去画像の期限は守る。
2. enabled / pause / shutdown を確認。
3. OS foreground metadata を取得。不明・取得失敗・除外対象なら画像取得前にスキップ。
4. 各モニターを別画像として取得し、識別子・bounds・取得時刻を保持。
5. Foreground を再取得。取得中に切り替わった場合は保存・解析せず破棄。
6. 各画像を32×32のRGB特徴量に縮小し、正規化平均絶対差を算出。
   最大差が閾値以下、モニター構成とforegroundが同じ、時間gapが小さい場合は重複扱い。
   最後に解析した画像と比較するため、少しずつ蓄積する変化も検出できる。
7. 新規画面のみRAM内で構造化解析。成功/失敗と設定からEvidence保存を判定する。
8. pause/resume の世代番号を再確認し、停止前の解析結果・失敗Evidenceが後から保存されることを防止。
9. Schema検証に通った構造化結果だけをRecordとして保存。画像参照の空リストを許容する。

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
毎回のcapture成功やduplicateをINFOへ追加しない。画像/Base64、巨大なrequest/response、
multipart bodyはActivityログへ出力しない。既存の小さなLLM lifecycle eventsは維持する。

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

## Fine-grained ActivitySession / Persistence

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
SQLiteのpayloadはRecord/Sessionの型付き構造化情報のみ。画像BLOB/Base64、
Vision request全文、未検証の巨大response全文は保存しない。
画像名に保存したcapturedAtでretention判定し、シンボリックリンクは辿らない。
DB保存失敗後の孤立画像もretention対象になる。

## Semantic Session / Primary・Secondary・Background

Phase 3 / 3.1の表示改善は二段階集約とする。永続化された細粒度Sessionの境界は変更しない。
`ActivityRolePolicy` が元Recordから役割を推定し、`SemanticSessionPolicy` が意味的に近い観測を
表示用の `SummarySegment` にまとめる。SegmentがSemantic Sessionの役割を担い、
`roles.primary / secondary / background`、推定confidence、判断根拠コード、全Evidence、
参照先の `fineSessionIds` を保持する。追加のVision呼び出し・画像読出し・DB書込みは行わない。

Primary判定はforeground processとcandidate applicationの対応、foreground title中のservice /
content / project候補を材料にする。既知のアプリ表記差（WindowsTerminal / Terminalなど）は正規化する。
project名だけでは主活動を決めず、ブラウザで複数の異なる候補が同点ならPrimaryは未判定にする。
foreground processとの一致を必須にし、8点を付与する。対応する候補についてのみ、
タイトル中のservice一致を5点、content一致を4点、project一致を1点加算する。
別プロセスの画面候補は、タイトルに名前があるだけではPrimaryにならない。
scoreが12以上ならモデルconfidence×0.95、それ未満は×0.75とし、
`primary-confidence-threshold`（既定0.5）未満、未知カテゴリ、異なる候補の同点はunknownとする。
Segmentの代表はforegroundで裏付けられた候補の観測推定秒数で重み付けする。
semantic集約では支持観測が半分未満なら未判定、confidenceは支持観測秒数で重み付けする。
通常上限を超えたgapで結合した場合はconfidenceを0.85倍にし、閾値を下回ればunknownへ戻す。
これは校正済み確率ではなく補助指標であり、操作・集中の測定値ではない。

監視カテゴリ、btop / Grafana / system monitor / ログ監視等の候補は背景役割を持つ。
ただしforegroundで裏付けられればPrimaryになれる。固定的に除外するblacklistではない。
残る候補はSecondary。全画面のchangeAmountを特定ウィンドウの操作に帰属させることはできないため、
今回のPrimary昇格には使わずEvidenceに保持する。低レベル入力監視は追加しない。

結合規則:

- 開発カテゴリを正規化し、同じproject文脈ならTerminal / GVIM / GitHub等の切替を許容する。
- SecondaryやBackgroundだけの変化は境界にしない。異なるcanonical categoryの上位テーマ集約はSummary専用層で行う。
- 異なる既知projectは結合しない。project不明の観測を挟んでも別projectへ連鎖結合しない。
- その他のカテゴリはservice / applicationやprojectの共通性も確認する。未判定同士は同じforegroundと候補集合が必要。
- 未観測gapは直前の推定区間の終端から測る。`SessionGapPolicy`の通常上限は120秒、最大は300秒。
  120秒以内は通常の結合候補。120秒超〜300秒は同じcategoryと同じ既知project、または同じservice/applicationという強い一致が必要。
  300秒超は結合しない。小さなgapの連鎖も、1 Segmentの累積未観測が300秒を超える結合は止める。
- A→B→Aと主文脈に戻る場合、Bの区間が`summary-brief-switch-seconds`（既定120秒）以内なら表示上まとめる。
  継続する新しいPrimaryや戻りのない変化は分割する。短いBも元Evidenceから削除しない。
- continuity境界（pause / 除外 / 失敗 / 再起動）と設定zoneの日付境界は越えない。

`observedSeconds` は次の観測・日末・検索区間でクリップし、重複を除く。
まとめた区間内の欠測は `unobservedSeconds` に分離する。細粒度Sessionにも同名の計算メソッドがある。
gapを作業時間や集中時間として補完しない。

### Canonical category / 表記正規化

`ActivityVocabulary` のカテゴリは development / research / documentation / communication /
social / media / shopping / monitoring / navigation / idle / other / unknown。
coding・debugging等はdevelopment、snsはsocial、video・musicはmediaに正規化する。
Terminal / Shell / Web / local等はカテゴリとして採用せずunknownとする。
application（道具）、service、categoryは分離し、genericなservice候補（local / Web / Shell等）も表示から除く。

正規化するのは役割付け・集約用projection。元Recordの自由文字列typeはVisionの原候補として残し、
既存DBのEvidenceを改変しない。正規化後の意味カテゴリは `ActivityRoles.category()` で取得する。
projectはNFKC・小文字へ正規化（Rei→rei）。Twitter / X (Twitter)は内部値x、表示名Xへ統一。
Terminal / Local terminal / PowerShell等は道具のterminalへ正規化し、表示名はターミナル。
同じ正規化をprocessとの照合にも使用する。

旧 `summary-gap-seconds` を明示している場合は、互換用の最大gap・累積欠測の追加上限として扱う。
未設定なら新しい120 / 300秒を使う。新しい上限は0以上かつ通常≤最大、confidence閾値は0〜1。

## Summary Segment / User-facing Summary

`ActivityRecord` はOS evidenceと元のVision推論を保持する。
Fine-grained `ActivitySession` は詳細な時系列とrecordIdsを保持する。
`SummarySegment` はその上の表示用projectionであり、元Recordと細粒度Sessionを消さない。
`SemanticSessionPolicy`の役割付き区間を、`SummaryGroupingPolicy`がより広いthemeへまとめる。
social / media / shoppingはweb-browsing、development / research / documentationはwork-development。
異なる作業カテゴリの結合には同じ既知projectが必要。異なるprojectは短い往復切替でも結合しない。
themeは表示用であり元のcategoryを上書きしない。`primaryCategories`に構成する主活動カテゴリを残す。
この層も同じgap上限・累積欠測上限・continuity・日付境界を守る。
`ActivitySummaryFormatter` はSegmentの役割から活動中心の短い日本語を生成する。
Visionの長文summaryを転記しないため、各行の免責文・ウィンドウ名列挙を抑えられる。
追加LLMは使用しない。時間計算と境界はコードだけで決める。

先頭に不確実性の説明を一度置き、各ブロックは主活動の推定と補足表示（最大3種類＋「など」）にする。
未知の場合もsecondary / backgroundから確認できる表示を最大3種類補足する。
未観測率（未観測秒数÷区間全体）が10%未満なら注記省略、10〜30%なら「一部未観測時間あり」、
30%超なら時間範囲を「観測できた時間帯のみ」と限定し「未観測の割合が高い期間」と明示する。
秒数の技術的表示はSummaryでは出さず、構造化Segmentに保持する。
「開発・確認作業」までに留め、候補にない「バグ修正」や実際の視聴・操作を断定しない。
Primaryを特定できない場合は、その旨を出す。見えているだけの活動を勝手に主活動に昇格させない。

提示例相当の合成fixtureでは09:08–09:46の5細粒度Sessionが1 Segmentになり、
交互にTerminal / GVIMを前面にした20細粒度Sessionも1 Segmentになる。
半日5〜10・1日8〜15ブロックは目標粒度であり、件数を達成するために異なるprojectや長い欠測を強制結合しない。
Phase 3.1の半日合成fixtureは24細粒度Session→6テーマ。実機履歴全体の平均Segment数は未測定。
将来のfocusMinutes / contextSwitchCount等はSummary文字列から逆算せず、元Record・細粒度Session・
Evidenceから算出する。Productivity Score、行動評価、週次/月次分析、Adaptive Coachingは追加していない。

## Query / Slash commands / Summary

### Phase 3.2 / 3.4: 時間帯の傾向と表示品質

`/activity summary`には表示専用の `TrendSummarySegment` を使用する。
Phase 3.1の `SummarySegment` / SemanticSessionPolicy / gap policyは変更せず、
その上に `TrendSummaryPolicy` を追加した。元の永続Fine-grained ActivitySession、
Raw Evidence、Primary / Secondary / Background、confidenceを上書きしない。
`sourceSegments` に元projection、`fineSessionIds` に細粒度Session参照を保持する。

新たにまとめる時間幅は最大60分、隣接した観測区間間のgapは最大20分（ポリシーの既定値）。
単独ですでに長い連続区間は分割を強制しない。同じ日付内で、上位テーマ、foregroundで裏付けられた
project、service/applicationの共通性、短い切替を使って候補をまとめる。
unknown / otherは近隣の時間帯へ吸収できるが、既知の活動として再ラベルしない。
異なる既知projectは分割する。未知の区間を挟んだprojectの連鎖結合も防ぐ。
Phase 3.1の300秒上限は元projectionに適用され、傾向表示にまで流用しない。

観測推定区間を元Recordからクリップして集計し、`observedSeconds` / `unobservedSeconds`に加え、
`knownSeconds` / `unknownSeconds`を保持する。otherは表示用の未判定時間に含め、原カテゴリは残す。
既知のPrimaryが観測時間の半分以上ならそのカテゴリをテーマに用い、それ未満なら可視候補も
画面表示の傾向として参照する。操作や実利用の時間には変換しない。

continuityは以下の**観測の性質**を表す。

- `CONTINUOUS`: 同系統の既知活動だけ、同じcontinuityId、最大gap120秒以内、未観測率10%以下。
  実際に操作し続けたことを証明する値ではない。
- `INTERMITTENT`: 欠測、unknown / other、continuityIdの変化などを含む。
- `MIXED`: foregroundで裏付けられた複数系統の活動を含む。欠測の有無・秒数は別途保持する。

themeはdevelopment-research / web-browsing / communication等とmixed-work / mixed / unknown。
元の細粒度categoryを上書きしない。projectはforegroundで裏付けられた候補だけを見出しへ使用する。
表示は確信度と観測密度に応じて「〜が中心」「観測できた範囲では」「断続的に見られました」を使い分け、欠測中の継続を主張しない。
未観測率の定型注意を各行へ重ねず、未判定を含む場合だけ短く補足する。
otherだけの場合も「その他の活動が中心」とは出さず、主活動を判定できない旨を表示する。

`ActivityDisplayLabels` は表示だけを正規化する。browser→ブラウザ、cmd→ターミナル、
gradle→ビルド、python→Python関連、shopping→ショッピング、video→動画、openai→AIツール、
chatgpt→ChatGPT、notion→Notion、asus→ASUS。未知の固有名詞を推測で書き換えない。
補足は最大4ラベル。妥当なprojectとの対応、Primaryとの一致、観測秒数で重み付けした出現頻度の順に選ぶ。
同一観測内の重複ラベルを頻度へ二重加算しない。

#### Phase 3.4: raw / canonical / display と意味役割

Raw Evidenceは変更せず、表示レイヤーの `ActivityDisplayLabels.canonical` と `label` で別々に扱う。
例えばWindowsTerminal / Windows Terminal / Windows-Terminal / cmd / PowerShell / Local terminalは
canonical=`terminal`、display=`ターミナル`。Text Editor / text editor / Local editorは`editor`→`エディタ`。
Local log file / log fileは`log-file`→`ローカルログ`、Twitter / X (Twitter) / X (旧Twitter)は`x`→`X`。
NFKC、case、空白、既知aliasの区切り記号を正規化し、表示ラベルをcanonical化したキーで重複除去する。
未知の固有名詞の綴りや意味は推測で変更しない。Activity判定用の既存Vocabulary / RolePolicyは変更しない。

project、service/application、activity、content/topicは別の役割として扱う。
Summaryのproject候補は、foregroundで採用された候補のうち英字始まりの2〜60文字の識別子
（英数字・`_`・`.`・`-`）に限定し、カテゴリ、既知サービス/アプリ、同じEvidence内のサービス/アプリとの
一致を除外する。`rei` / `yagisan-reports`等は保持し、`x browsing` / X / GitHub / WindowsTerminal等を
projectにしない。`機能設計の提案`等の自然文トピックもproject扱いせず、原文はEvidenceに残す。
不確かな候補はSummary上のproject名を省略する。これは保守的な構文・役割検証であり、実在する
リポジトリの照合ではない。日本語名や空白を含む正当なprojectも一般化される制約がある。

#### Phase 3.4: soft maximum duration と意味境界

`TrendSummaryPolicy.softMaximumDuration`の既定値は45分。これは表示候補の内部境界を再検討する
トリガーであり、45分の位置で切るタイマーではない（現時点ではJavaポリシーの値で、YAML設定項目はない）。
Phase 3.2の新規結合60分・隣接gap20分という候補生成の制限は維持する。
55分ずっと同じ傾向なら1件のまま。既存の長いsource projectionの内部も元Evidenceを見て検討する。

- 妥当なprojectが別projectへ変わった場合は長さによらず分割。unknownを挟んでも競合を隠さない。
- dominant themeの持続的変化は45分未満でも分割。前後各10分以内の観測推定秒数を集計し、
  各側で同じテーマが5分以上、かつ観測時間の70%以上ある場合を意味のある切替とする。
  development / research / documentationはwork、social / media / shoppingはWeb/娯楽のfamily。
  work ↔ leisureもこの判定に含める。unknownは既知カテゴリへ変換せず、比率の分母に残す。
- 45分を超えた場合は、さらに持続的なforeground文脈の変化、5分以上の未観測gap（両側に5分以上の観測）、
  同family内のPrimary categoryの変化を境界候補にする。同じ妥当なproject内のツール変更だけでは分けない。
- project競合を優先し、次にtheme、foreground、gap、categoryの順で選ぶ。同順位では時間的に中央に近い
  境界を選び、子区間も同じ基準で評価する。短い寄り道や1分のサンプルだけでは持続的変化にしない。

分割対象はTrendSummarySegmentだけ。元Record、Fine-grained Session、Phase 3.1 SummarySegment、
元のroles/confidenceは変更しない。子のEvidenceと観測秒数を再集計し、`sourceSegments`は元projectionを
そのまま参照する（同じ親が複数の子から参照され得る）。Timeline APIの`fineSessionIds`は子のEvidence・
時間との対応で解決し直す。分割によって区間外になった未観測gapを活動時間へ足し込まない。

#### Phase 3.4: 文面の自然化

追加LLMは呼ばず、コードで安全な文面を選ぶ。既知Primaryの時間が観測の70%以上、観測秒数で重み付けした
Primary confidenceが0.7以上なら活動ベースの表現を使う。confidence計算も既存RolePolicyに従う。

- CONTINUOUSかつ未観測率10%以下で上記条件を満たす場合は「rei関連の開発・確認が中心」。
- INTERMITTENTや未観測率が高い場合は「観測できた範囲では」「断続的に」を残す。
- MIXEDは「開発・確認とSNS閲覧が混在」。確信度が弱いときは「〜関連の画面が混在していました」へ戻す。
- 全て未判定なら主活動を判定できない旨を表示。一部未判定も短く補足する。

冒頭の画面観測・推定に関する説明は維持する。「集中」「バグ修正」等、Evidenceにない操作や成果は生成しない。
service/applicationの補足は表示された事実として記述する。

追加LLM・画像取得・DB更新は不要。`ActivityTimeline.trendSegments(day)`と`trendSummary(day)`が
新しい読み取り用API。既存query / findBetween / summarySegments / summaryBetween / summaryおよび
自然言語ツールの挙動は維持する。将来のAnalyticsは元Record・細粒度Sessionを入力にし、
傾向Segmentの時間幅を集中時間・SNS利用時間へ転用しない。

### コマンドと既存API

```text
/activity today
/activity yesterday
/activity summary
/activity 2026-09-22
/activity pause
/activity resume
```

`summary` は今日の時間帯の傾向を `TrendSummaryFormatter` で表示する。
today / yesterday / 日付指定（引数省略を含む）はPhase 3.1の `ActivitySummaryFormatter` 表示を維持する。
追加LLMは不要。
記録がない場合は「記録なし」と返し、何もしていなかったとは断定しない。
pause はメモリ上の状態であり、再起動時には enabled 設定に従う。
resume は `enabled=false` を上書きしない。

`findByDate` は設定タイムゾーンの一日（DSTを考慮）。
`findBetween(startInclusive,endExclusive)` は半開区間で重なるSessionを返し、
境界を指定時間にクリップする。最大31日。Record IDsは元のSessionの参照を保持する。

`summarySegments(day)` / `summaryBetween(start,end)` は役割・元Evidence・細粒度Session参照付きの
Segmentを返す。`findRecordsBetween(start,end)` は推定区間が重なる元Recordを改変せず返す。
どのSummary queryも読み取り専用で、以前の日付に蓄積したデータも再収集せず新しい表示になる。
自然言語の振り返りは `activitySummary`、詳細取得は既存の `activityTimeline` / `activityBetween` を使用する。

通常のChatには `activitySummary(date)`、`activityTimeline(date)` と `activityBetween(startInclusive,endExclusive)` を登録。
「昨日の夜なにしてた？」等は既存Agentが日時コンテキストを参照してToolを呼び出す。
後者はオフセット付きISO-8601日時を受け付ける。自然言語の固定パターン表は追加しない。
専用Timeline画面やクライアント独自Slash parserは今回追加していない。

## Tests / TDD

### InvalidOutputの切り分け

`Activity extraction failed (InvalidOutput)` はVision応答の検証に失敗してRecordを保存しなかったことを示す。
詳細診断では、例えば `/confidence:maximum` や `/:output_limit` のように
既知のフィールド位置・理由コードだけをWARNに出す。画像、タイトル、値、モデル応答全文は出さない。

| 理由 | 確認する点 |
|---|---|
| `output_limit` | `finish_reason=length`。`rei.llm.features.activity.max-output-tokens` とサーバーの生成上限・reasoning予算を確認 |
| `empty_response` / `empty_output` | 応答結果/最終contentが空。サーバーのreasoning分離やモデル互換性を確認 |
| `invalid_json` | JSON以外の説明・コードフェンス・reasoning混入・不完全JSONなど。構造化出力設定を確認 |
| `required` / `type` / `maximum` 等 | 表示されたフィールドのSchema制約違反。値そのものはログに残さない |
| `unknown_monitor` | 画像に渡したIDと応答中のmonitorが不一致 |

抽出リクエストのJSON Schemaでは、`activities[].monitor` のenumを、その回に送信した画面IDに限定する。
プロンプトにも画像順とIDの対応を明示する。応答側のID検証も維持し、Schema制約を無視した応答は保存しない。

vLLMを使う場合は、利用バージョンとモデルに適したreasoning parserとJSON Schema出力の組合せを確認する。
設定条件は [vLLM Structured Outputs](https://docs.vllm.ai/en/latest/features/structured_outputs/) を参照。
元の理由なしWARNだけから、サーバー設定やトークン不足を断定することはできない。
診断を増やしてもSchema検証は緩めず、不正データの保存や画像の自動追加送信は行わない。

外部Desktop/Vision/ScreenshotStore/ActivityStoreはPortとして差し替える。
ポリシー→取得/解析→保存/検索の順にテストを先に追加し、コンパイル失敗のRedから実装した。
summaryの言い回しによる過分割、pause/resumeを越えた誤結合についても、
失敗する回帰テストを確認してから修正した。

`ActivityPolicyTest`、`ActivityCaptureTest`、`ActivityExtractionTest`、
`ActivityTimelineTest`、`ActivityIntegrationTest` が新機能を検証する。
Memory-Firstの検証は `MemoryFirstVisionTest`（内部ディスクキャッシュ禁止・PNG精度）、
`MemoryFirstStorageTest`（画像なしのDB/Timeline/Summary・Evidence retention）と
`ActivityCaptureTest` の保存回数・呼出順序・失敗/除外/pause時のポリシーテストで行う。
実機画面のキャプチャと実際の外部Vision API呼び出しは自動テストでは実行しない。

## Phase 3.5: Behavior Evaluation / お小言

デフォルト無効のBehavior Evaluationを追加。詳細Sessionと対応する元RecordのPrimary観測区間から、
social / media / shopping / gamingの連続時間とeligible observed timeに対する割合をコードで評価する。
SummarySegmentの時間幅は評価に使わない。通知可否・cooldown・回復stateは文章生成と分離し、
通知許可時だけ既存キャラクタープロンプトで短い発話を生成する。

`/activity behavior on|off|status|evaluate`で操作できる。evaluateは通知しない手動評価。
全設定・既定値、unknown/未観測の扱い、再起動時のcooldown保持、失敗時の扱いは
[Activity Behavior Evaluation](activity-behavior-evaluation.md)を参照。

## 将来 Phase 4〜5

Recordを失わず、時刻・推定duration・foreground・モニター・変化量・並行Activity・
project/content/service候補・confidence・継続境界を保持しているため、
context/project switch、セッション長、primary activity/projectなどを再集計できる。
focus/idle/entertainment minutesを作る場合も、今のデータだけで実測集中時間と断定せず、
新しいevidenceやユーザー定義を追加する。サンプリング間の細かな切り替えは復元できない。

高頻度化時は日次projection再構築を差分更新へ変更し、schema migration、
集計バージョン、日次summaryキャッシュを加えられる。
Phase 4以降のProject/Task/Working Set深い統合、
週次/月次Analytics、2〜6のProductivity Score、Adaptive Coachingは意図的に未実装。
