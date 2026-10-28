# ファイル書き込み改善

基準: origin/main `9b1d3017` (2026-10-28、Asia/Tokyo)。

## Phase 0: 調査と比較方針

単一 Change Set は SHA-256 に束縛した部分編集、元版上の一意一致と重複範囲検証を持つ。
複数 Change Set は UPDATE/CREATE/DELETE/RENAME の全文入力のみ。
両者とも既存の提案保存・所有権・ポリシー・一回 Apply を維持する。
複数の journal、baseline backup、rollback、UNKNOWN、外部変更保護は再利用する。
単一と複数の部分編集・差分生成を共通化し、別のキャッシュや承認経路は作らない。

現状の制約: 各64 KiB、複数の前後合計256 KiB、保存 JSON 1 MiB。
UTF-8のみの Change Set に対し旧 applyTextDiff は CP932等にも対応するが直接 truncate する。
旧ツールの重複探索は重なる一致を見落とす。単一の差分は共通prefix/suffixのみ省略し、
複数は全文を返す。Applyでも同じ差分を返す。プロンプトの1000文字分割・writeMultiFile優先を整理する。

ベンチマークは固定fixture、入力/返却JSON bytes、差分bytes、ツール呼び出し数、
実行時間と成功/拒否状態を保存する。I/OはJFRの対象source/stagingのFileRead/FileWrite
イベントで観測し、syscall/open回数やSQLite native I/Oとは区別する。
未対応ケースは成功率ゼロの変更として扱わず UNSUPPORTED を記録する。
実LLMのトークン、タスク時間、成功率は測定環境がないため未測定。

## Phase 1: 編集と保存の共通化

`TextEditEngine` は元版上の一意一致（重なる一致を含む）、編集範囲の重複、入力予算を
検証し、生成内容と変更範囲を返す。単一・複数Change Setと旧applyTextDiffが利用する。
既存のUTF-8 writerから抽出したbyte stagingを旧ツールでも利用する。force、権限コピー、
baseline/stage再検証、atomic move、保存結果確認を共通化し、非atomic fallbackは行わない。
旧ツールは元のcharsetでstrict encodeし、BOMと改行を保持する。書けない文字は保存前に失敗する。
writeMultiFileの直接全文置換・追記、バイナリ書き込みは既存経路を維持する。

ツールの選択は、UTF-8部分編集はChange Set、複数変更はDocument Change Set、
レビュー付き作成/削除/renameもDocument、直接全文書き込み/追記はwriteMultiFile、
非UTF-8局所編集はapplyTextDiff、バイナリはwriteBinaryFile。
文字数だけを理由に分割する旧案内は廃止した。

## Phase 2: 複数部分編集と独立した上限

既存のoperations、全文方式、Javaの5引数Operation constructorを維持する。
UPDATEのみ、全文と排他的にbaseVersion/editsを受け付ける。versionは読み取り結果の
SHA-256をそのまま利用し、sha256: prefixも受け付ける。

```json
{"request":{"operations":[{"kind":"UPDATE","path":"src/App.java","baseVersion":"<readMultiFileのversion>","edits":[{"oldText":"int timeout = 30;","newText":"int timeout = 60;"}]}]}}
```

| 予算 | 既定値 / 上限 | 設定（JVM system property） |
| --- | --- | --- |
| 元/生成後ファイル | 各1 MiB | rei.text-write.file-bytes（最大1 MiB） |
| 1ファイルの部分編集入力 | 64 KiB | rei.text-write.edit-bytes（最大64 KiB） |
| 全文方式の各テキスト | 64 KiB（既存契約） | 固定 |
| 要求テキスト合計 | 256 KiB | rei.text-write.request-bytes（最大1 MiB） |
| 元/生成後合計・staging内容 | 8 MiB | rei.text-write.total-bytes（最大8 MiB） |
| 保存JSON / journal JSON | 16 MiB | rei.text-write.payload-bytes（最大16 MiB） |
| 1回の差分本文 | 16 KiB | rei.text-write.diff-bytes（最大64 KiB） |
| 操作/target/hunk | 32 / 64 / 各64 | 既存の固定上限 |

設定は少なくとも256 bytes。読み取り側の1 MiBと整合する範囲に制限する。
要求上限はテキストpayloadのUTF-8 bytesであり、JSON envelopeのbytesとは別。
JSON escapeで保存上限を超える場合も明示拒否する。全baseline/提案内容は内部で保持し、
LLMに全文入力を要求しない。Document保存形式のoperationsには操作メタデータだけを残し、
before/afterの二重保存を避ける。旧保存JSONは引き続き読める。
既存の128件/Project、全体1024件、terminal履歴の保持とUNKNOWNの保護は維持する。

エラーはVERSION_MISMATCH、AMBIGUOUS_MATCH、OVERLAPPING_EDITS、TEXT_NOT_FOUND、
SIZE_LIMIT_EXCEEDED、UNSUPPORTED_ENCODINGを区別する。失敗した提案は保存しない。
適用のSTALE/ROLLED_BACK/UNKNOWNはそれぞれVERSION_MISMATCH/TRANSACTION_FAILED/UNKNOWN。
可能な場合path、editIndex、候補行（最大8）、currentVersionと再確認方法を返す。
エラーに本文は含めず、追加の本文キャッシュも作らない。
retryableは読取り・修正後の再提案可能性で、safeToReplayは常にfalse。
Tool境界はtyped failureだけをJSONとして返し、TOOL_FAILEDを保持する。
権限拒否、キャンセル、任意の内部例外を成功として返す経路は追加しない。

## Phase 3: 差分と結果の返却

`TextDiff` を両Change Setで共有する。行単位Myers、文脈3行、複数hunk、
末尾改行マーカー、作成/削除の/dev/nullを扱う。差分探索は最大256 edits/200万比較に制限し、
超過は正しい領域全置換の差分へfallbackする。推測のパッチは生成しない。
既存のcredential maskの実装を純粋なtoolcontract境界へ移し、eventの旧APIは委譲する。
循環依存は増やさず、解消した既存依存だけbaselineから削減した。

Propose/Inspectのdiffは予算付きpreview。diffInfoはbytes、truncated、omittedBytes、
省略されたpathとhunk番号の区間、nextOffsetを返す。
getTextChangeSetDiff / getTextDocumentChangeSetDiffはREADのみ。
id、正確なproposalSha256、返却されたoffset、maxBytes（256..65536）で続きを取得できる。
cursorはUTF-16位置のopaque値で、改変せず使う。UTF-8 code pointを途中で切らない。
truncatedはページ外の内容がある意味で、終了判定はnextOffset=-1を使う。
omittedBytesは現在ページ外の全bytes。マスク後の同じ保存提案から毎回再生成する。
別Project/root/sessionのDocumentや異なる提案SHAを使った取得は拒否する。
既存の全文export（単一）とInspect、詳細ページ取得は維持する。
省略は承認範囲を狭めない。既存ポリシーによるApplyの対象は常に提案SHAに束縛した全文。

Applyは互換のView/既存getterを維持しdiffを空にする。nullの新項目はJSONから省く。
提案の要約changes、changedFiles、changeCountと現在のSHA/currentMatchesを返す。
changeCountは入力edit数ではなく連続した変更行領域の数。rangesは1-based半開行区間で、
挿入/削除の空区間はstart=end。件数と範囲は保存提案を記述し、成功判定はstatusを使う。

## Phase 4: 検証情報と再読み取り

既存のApply後の内容照合・receiptとSHAを利用し、currentMatches=trueなら保存確認だけの
全文再読は不要と案内した。構文・意味・テストの成功は別の検証として明記する。
APPLIEDの再取得は歴史receiptで、現在版が異なる場合はcurrentMatches=false。
過去の成功を現在の内容の証明に読み替えない。
競合は現在版/対象箇所を読み直して新しい提案を作る。旧承認の自動rebaseは実装しない。
UNKNOWN/失敗時は保存IDをinspectし、既存の明示復旧を使う。別の版管理/cacheは導入しない。

ProposeのoperationIdは今回は追加しない。保存IDを知っているApplyの応答喪失は、再起動後も
同じreceiptを返し再公開しないことをテストした。Propose応答喪失の発生頻度・transport再送費用は
実環境で未測定で、重複提案だけではファイルを書き換えない。追加のキー保持/期限/所有者管理を
必要とする根拠が得られてから導入を判断する。

## テストと再現方法

TDD記録: 旧ツールの重なる一致とBOM保持は2件Red→Green。
新しい案内、複数部分編集、対象サイズ、構造化診断、分離hunk、差分予算/ページ、
簡潔な結果、保存後の案内もRed→Greenで確認した。
既存の全文入力64 KiB契約を緩める回帰は既存テストが検出し、部分編集だけ大容量化して修正した。
新しいProject fixtureのUUID誤りはテスト側の誤りとして修正した。
独立したgit applyで、固定seedの40ケース、作成/削除/末尾改行/探索fallbackの4ケースを
実際に適用し、bytesに対応する内容を照合する。

```powershell
# integrationを含む関連回帰
.\mvnw.cmd -B -Dtest.excludedGroups=live '-Dtest=LegacyWriteSafetyTest,FileWriteExtensionTest,TextDiffContractTest,WriteDiffResultTest,WriteVerificationTest,MultiHunkChangeSetTest,TextChangeSetTest,TextChangeSetToolTest,TextDocumentChangeSetTest,TextDocumentChangeSetToolTest,ToolEventCallbackDecoratorTest' test
# 全体回帰（既存の時間上限付きrunner）
.\.github\ci\full-test.ps1
# 現版ベンチマーク
.\mvnw.cmd -B -Dtest.excludedGroups=live -Dtest=FileWriteBenchmarkTest test
```

Mockitoの動的attachを制限する環境では、ローカル実行時にMockito javaagentをargLineで指定する。
Windows sandboxでは権限コピー後のatomic moveやjunction作成が拒否されるため、
同じテストを通常権限で再実行して環境要因を切り分けた。失敗をskipに変更していない。
基準ベンチマークはcommit c16a13d5の同名テストで再現できる。

## 測定結果（最終版）

Windows / JDK 25、各ケース1回、JFR有効。時間はcold起動・SQLite・JFRの影響を受け、速度改善の一般的な証明ではない。
入力JSONはPropose要求（CP932のみ旧ツール入力）、出力はPropose+Apply結果の合計。
ツール呼び出しは既知path/versionからの書き込み経路のみで、事前読取り・意味検証・LLM生成を含まない。
source/stagingのI/OはJFRイベント数で、OS syscall回数ではない。競合/UNKNOWN fixtureの外部書込み注入もこのイベント数に含む。
保存確認のための追加read Toolは呼んでいない。基準と最終版の生データはfile-write-baseline.json / file-write-optimized.json。

| ケース | 入力JSON bytes 前→後 | 返却JSON bytes 前→後 | 差分bytes 前→後 | 状態 前→後 |
| --- | ---: | ---: | ---: | --- |
| single | 201 → 201 | 1689 → 1782 | 319 → 183 | APPLIED → APPLIED |
| distant | 258 → 258 | 40019 → 2112 | 18564 → 356 | APPLIED → APPLIED |
| multiple | 39712 → 594 | 85265 → 3839 | 39624 → 712 | APPLIED → APPLIED |
| large | 201 → 201 | 30 → 1782 | 0 → 183 | UNSUPPORTED → APPLIED |
| ambiguous | 187 → 187 | 59 → 505 | 0 → 0 | REJECTED → REJECTED |
| conflict | 201 → 201 | 1687 → 2258 | 319 → 183 | STALE → STALE |
| applyFailure | 39726 → 480 | 85366 → 3667 | 39638 → 366 | ROLLED_BACK → ROLLED_BACK |
| rollback | 39726 → 480 | 85366 → 3667 | 39638 → 366 | ROLLED_BACK → ROLLED_BACK |
| unknown | 39726 → 480 | 86310 → 4600 | 39638 → 366 | UNKNOWN → UNKNOWN |
| crlfBom | 201 → 201 | 1745 → 1798 | 333 → 191 | APPLIED → APPLIED |
| cp932 | 未測定 → 82 | 未測定 → 71 | 未測定 → 0 | 未測定 → APPLIED |

| ケース | Tool calls 前→後 | FileRead events 前→後 | FileWrite events 前→後 | 経路時間ms 前→後 |
| --- | ---: | ---: | ---: | ---: |
| single | 2 → 2 | 8 → 9 | 1 → 1 | 198.82 → 241.45 |
| distant | 2 → 2 | 8 → 9 | 1 → 1 | 113.14 → 153.51 |
| multiple | 2 → 2 | 22 → 22 | 4 → 4 | 381.80 → 456.61 |
| large | 1 → 2 | 0 → 9 | 0 → 1 | 6.25 → 131.24 |
| ambiguous | 1 → 1 | 1 → 1 | 0 → 0 | 4.54 → 14.02 |
| conflict | 2 → 2 | 3 → 3 | 1 → 1 | 44.78 → 64.03 |
| applyFailure | 2 → 2 | 22 → 22 | 4 → 4 | 200.54 → 293.76 |
| rollback | 2 → 2 | 25 → 25 | 4 → 4 | 210.95 → 270.85 |
| unknown | 2 → 2 | 23 → 23 | 5 → 5 | 249.08 → 274.94 |
| crlfBom | 2 → 2 | 8 → 9 | 1 → 1 | 126.90 → 128.54 |
| cp932 | 未測定 → 1 | 未測定 → 6 | 未測定 → 1 | 未測定 → 56.63 |

固定11ケースは11/11で期待状態（意図した拒否/復旧/UNKNOWNを含む）を満たした。各n=1の観測であり実タスク成功率ではない。
複数ファイルの要求は98.5%、返却は約96%削減。離れた変更の中間本文の再掲も削減した。
単一の小さな編集では検証メタデータの固定費で返却が増える。保存後の照合でreadイベントも1件増え、書込みbytesは原則同じ。
Tool呼び出し回数は比較経路で同じ。LLMが実際に再読を省く割合、総トークン、総タスク時間、再試行回数は未測定。
64 KiB超は旧版で非対応のため、性能削減率を算出しない。CP932の基準時間は未測定。
