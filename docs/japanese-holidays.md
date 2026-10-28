# 内閣府CSVによる日本の祝日・休日

`JapaneseHolidayService` は全Project共通の `ReiDataDirectory.current()/syukujitsu.csv` を参照します。
`rei.data-dir` システムプロパティ、`REI_DATA_DIR`、OS標準保存先の解決は既存処理を使います。
CSVの自動取得は行いません。利用者が次の公式ファイルを手動で更新してください。

- [内閣府の説明・収録年](https://www8.cao.go.jp/chosei/shukujitsu/gaiyou.html)
- [公式CSV](https://www8.cao.go.jp/chosei/shukujitsu/syukujitsu.csv)

## 配置と対応年の設定

Windows PowerShellの例（既存の `REI_DATA_DIR` があればその値を使います）:

```powershell
$holidayDir = if ($env:REI_DATA_DIR) { $env:REI_DATA_DIR } else { Join-Path $env:LOCALAPPDATA 'Rei' }
New-Item -ItemType Directory -Force -Path $holidayDir | Out-Null
# ブラウザーで公式CSVをダウンロードし、文字コードを変えずにコピー
Copy-Item -LiteralPath "$env:USERPROFILE\Downloads\syukujitsu.csv" -Destination (Join-Path $holidayDir 'syukujitsu.csv')
```

POSIXでは `$REI_DATA_DIR/syukujitsu.csv` にコピーします。未設定時はLinuxの
`${XDG_DATA_HOME:-$HOME/.local/share}/rei`、macOSの `~/Library/Application Support/Rei` が保存先です。
`-Drei.data-dir=...` を指定している場合はそちらを優先します。

公式ページの収録期間と取得したCSVが年全体を含むことを確認して、外部 `application.yaml` の
既存 `rei:` に次を統合してください（設定変更の反映は再起動）。

```yaml
rei:
  holidays:
    complete-years: '1955-2027'
```

上記は2026年10月に確認した公式CSVの例です。固定された将来の保証ではありません。
カンマ区切りの年と範囲（例 `'2020,2021,2026-2027'`）を設定できます。
既定値は空で、完全性を確認した年はありません。CSVに実際に行がある年と設定年の共通部分だけを
完全な対応年として扱います。CSVの最初・最後の日付と、1月1日から12月31日までの対応年は別です。

CSVは休日の列挙で、完全性を証明するメタデータを含みません。構文が正常でも、一部の行の欠落を
機械的に検知することはできません。途中までのCSV、部分抽出、未確認の将来年を完全な年として
設定しないでください。CSV更新時にも公式ページと照合が必要です。件数や最後の祝日から完全性を推測しません。

## 読み込み・障害時

MS932（Windows-31J）を明示し、UTF-8はBOM付きの場合のみ受け付けます。LF/CRLFと引用された二列にも対応します。
ヘッダー、列数、厳密な `yyyy/M/d` 日付、空名称、重複、空行、空ファイルを検証します。
想定外の形式を黙って読み飛ばしません。ファイルサイズ上限は2 MiBです。

起動時に読み込み、その後の問い合わせで最終更新日時・サイズ・fileKeyを確認します。
変更時だけ再解析し、成功時にimmutableなスナップショットを一括交換します。
明示的な `reload()` は同じ更新日時・サイズでも再読込します。バックグラウンド監視スレッドはありません。
更新中の問い合わせは直前の正常スナップショットを利用できます。CSVは一時ファイルに保存してから
置換する運用を推奨します。書き込み途中のメタデータ変化も検出して採用を拒否します。

未配置、権限エラー、破損でアプリ全体を停止しません。正常データが一度もなければ `UNKNOWN`。
再読込失敗時は旧データを保持し、`dataCurrent=false` を返します。
同じ失敗ファイルを毎回解析せず、更新または明示的reloadで再試行します。
内部の `getHolidayDataStatus()` でパス、存在、成功・試行日時、収録日・年、対応年、件数、エラー、最新性を確認できます。
LLM応答には絶対パスやファイル例外を含めません。

## 判定とツール

Javaから `JapaneseHolidayService.getHolidayInfo(LocalDate)`、`listHolidays(LocalDate, LocalDate)`、
`reload()`、`getHolidayDataStatus()` を利用できます。Springの共通BeanなのでProjectIdは不要です。
日付は `LocalDate`、診断時刻は注入された `Clock` の `Instant` を使い、OSロケールに依存しません。
現在日付を暗黙に選ぶAPIはありません。

LLMの `getHolidayInfo` の引数例:

```json
{"date":"2026-11-23"}
```

`HOLIDAY`、名称 `勤労感謝の日`、曜日、土日フラグ、休日フラグ、根拠、データ最新性を返します。

`listHolidays` の引数例:

```json
{"from":"2026-11-01","to":"2026-12-31"}
```

両端を含む最大366日を受け付けます。年越しも可能で、休日一覧と判定不能期間 `unknownPeriods` を別々に返します。
日付形式・存在・順序・期間上限の違反は安全な入力エラーです。
CSVにある日付は完全性未確認の年でも休日として返します。CSVにない日付は確認済み対応年のみ
`NOT_HOLIDAY`、それ以外は `UNKNOWN` です。`UNKNOWN` の休日booleanは `null`。
土日は独立情報で、通常の土曜日を国民の祝日と扱いません。
データ不足をLLMが推測で補わないようツール説明にも明記しています。

「休日」の分類はCSVに記載された日付だけに適用します。振替制度の1973年4月12日施行、
国民の休日の1985年12月27日施行、2007年1月1日からの連続祝日後への振替を区別します。
2007年より前の国民の休日は日曜日と振替休日を除外します。祝日移動や特例日はCSVをそのまま使い、
独自の春分・秋分計算や将来予測は行いません。
根拠となる隣接祝日が不足・曖昧なら `UNCLASSIFIED_DAY_OFF`（休日booleanはtrue）を返し、
休日である事実と法的な分類の不確実性を分けます。

分類の根拠:

- [現行祝日法と内閣府の解説](https://www8.cao.go.jp/chosei/shukujitsu/gaiyou.html)
- [1973年改正](https://www.shugiin.go.jp/internet/itdb_housei.nsf/html/houritsu/07119730412010.htm)
- [1985年改正](https://www.shugiin.go.jp/internet/itdb_housei.nsf/html/houritsu/10319851227103.htm)
- [2005年改正・2007年施行の概要](https://www.sangiin.go.jp/japanese/joho1/kousei/gian/162/meisai/m162090159014.htm)

## 検証と既存機能

固定fixtureのテストはネットワークを使いません。

```powershell
./mvnw.cmd '-Dtest=HolidayCsvTest,JapaneseHolidayServiceTest,HolidayClassificationTest,JapaneseHolidayToolsTest,HolidayRegistrationTest' test
./mvnw.cmd -Pfull test
```

公式ファイルの手動検証は次の明示的なコマンドで実施できます（通常テストはダウンロードしません）。

```powershell
./mvnw.cmd '-Dtest=OfficialHolidayCsvTest' '-Dholiday.csv=C:\path\to\syukujitsu.csv' test
```

Scheduler、Secretaryの既存実行条件・通知ポリシー・勤務曜日は変更しません。
将来はこの共通サービスを使って、土日祝除外の営業日、次営業日、祝日除外スケジュール、
データ不足時の実行ポリシーを上位層で実装できます。個人休暇、年末年始休暇、勤務形態は
別の業務カレンダーとして合成し、国民の祝日サービスへ混在させません。

### 依存関係予算の追加理由

依頼のPhase 3（LLM公開）と既存Global Rei Data Directory再利用に必要な
次の3クラス辺だけを、既存ゲートで追加エラーを確認後に個別に登録しました。
既存の循環があるため、同じ辺が複数の予算に現れます。ゲート自体の判定は変更していません。

| クラス辺 | 目的 | 予算 |
| --- | --- | --- |
| `LlmChatClientProvider -> JapaneseHolidayTools` | 機能別ChatClientの既存ツール登録 | C-business-cycles |
| `AiConfiguration -> JapaneseHolidayTools` | 既定ChatClientへのSpring配線 | C-wiring-cycles / D |
| `HolidayConfiguration -> ReiDataDirectory` | 環境変数処理を複製せず共通保存先を利用 | D / E |

承認根拠は今回の依頼の「既存の登録方式に従う」「既存のGlobal Rei Data Directoryを使用する」要件です。
将来のアーキテクチャ整理Phase 1で、既存のLLM/temporal間の循環を解消するときに
これらのC予算も削除します。機能追加のためにSchedulerやSecretaryへ逆方向の呼出しは追加していません。
