# ファイル操作の改善

調査基準: `origin/main` `dc833e532dad6602ee1bdf5c2da56298e11c18b7`、2026-10-08 (Asia/Tokyo)。

## 既存実装の確認

| 領域 | 実装 / 接続 | 検証対象 |
| --- | --- | --- |
| 読み取り / 一括読み取り / 複数条件検索 / searchAndRead | `core/Tools.java` の Tool callback。検索は `scanGrepMatches`、本文取得は `readFileSections` | `ToolsTest`, `FileOperationsOptimizationTest` |
| 検索キャッシュ | `core/searchcache/SearchResultCache`。以前は TTL のみで grep 結果を再利用 | `SearchResultCacheTest`, `SearchResultCacheSessionTest` |
| 編集 / Change Set | `TextChangeSetService`, `TextChangeSetRepository`, `TextDocumentChangeSetService`, `TextDocumentTransaction`。提案を永続化し Apply / Discard を別操作で実行 | `TextChangeSetTest`, `TextChangeSetToolTest`, `TextDocumentChangeSetTest` |
| Repository Map / Java AST | `RepositoryMapService` の JDK `JavacTask` / `Trees`、`SqliteRepositoryMapIndex` の任意永続化 | `RepositoryMapServiceTest`, `PersistentRepositoryMapTest`, `MultilanguageRepositoryMapTest` |
| 権限 | `ToolPermissionPolicy`, `ToolPermissionGuard`。登録済み callback の能力を判定 | policy / approval テスト |
| Resume | `checkpoint/PersistentCheckpointService`, `CheckpointReconciler`, `ResumeContextAdvisor`。Change Set 自身も durable claim と適用 receipt を保持 | checkpoint / Change Set テスト |
| コンテキスト圧縮 / 予算 | `ContextBudgetManager`, `ContextHistoryAdvisor`, `ToolResultCompressor`, `TokenEstimator`, `llm/ModelCallBudget` | contextbudget / Run budget テスト |
| 結果保存 / 再取得 | `RawToolResultStore`, `RawToolResultTools`。圧縮前の結果を保存 | `ToolResultCompressorTest` |
| Shell / HTTP / SSE / Native / SubAgent | 共通 Tool callback と捕捉 Run / Project context。HTTP と Native は Tool 専用の新しい transport を必要としない。SubAgent は `SubAgentRunner` の許可済み callback を使用 | Tools / HTTP / Client / Native / SubAgent 回帰検証 |

調査時点では、検索条件ごとのファイル再読、検索後の本文再読、5,000 行を超えても本文を削らない処理、外部変更を検証しない検索キャッシュが存在した。Change Set は単一ファイルの変更前後の全文を入力として要求していた。Java Map の Symbol は名前・種類・開始行・entryPoint のみで、宣言の終了位置やオーバーロード signature を持っていなかった。

## Phase 1: リクエスト内スナップショット

`FileSnapshots` はリクエストごとの immutable bytes と SHA-256 を保持する。検索条件間、検索と本文取得間、一括読み取りの重複した範囲で同じ bytes を使用する。リクエストをまたぐ TTL のみの grep 結果再利用を停止した。既存の SearchResultCache のクラスと書き込み時の無効化契約は残している。

単一ファイルは 1 MiB、リクエスト全体は 8 MiB、inventory / snapshot は最大 1,024 ファイル。容量超過は読み取りエラーであり、空ファイルとして扱わない。読み取りは同期し、同じ request-local cache への並列アクセスも1回に集約する。走査と読み取りで cancellation を確認する。

読み取り前後の file key / creation time / mtime / size を比較し、観測できた変更を拒否する。返却 version は実際に読んだ bytes の SHA-256。検索と本文は同じ snapshot の位置を使用する。次リクエストの継続では新しく bytes を読み、hash を比較するため同サイズ・同 mtime の変更も検出する。OS が外部 writer に提供する snapshot isolation はなく、読み取り中に属性まで復元する非協調 writer への厳密な isolation は保証できない。

`readMultiFile` / `searchAndRead` は総 5,000 行、各ファイル 1,000 行を実際の本文に適用する。source payload の UTF-8 byte budget は総 64 KiB。grep の本文にも同じ budget を適用する。JSON envelope、パス、エラーメタデータを含む最終 JSON 全体にも1 MiBの guard を適用し、超過は明示的なリソース上限エラーにする。巨大行は code point 境界で分割する。

返却 `version` は SHA-256 hex、`nextRead` は既存 `readMultiFile` にそのまま渡せる入力。`offset` は指定 startLine 内の UTF-16 offset であり、返却値を改変せず使う。`returnedBytes` は返却した source payload と行区切りの UTF-8 byte 数。search の `sections.startLine/endLine` は返却済みの行、`truncated` は source の省略を示す。巨大行の最後の行も部分返却になり得る。

```json
{"files":[{"path":"src/App.java","startLine":1,"endLine":2000}]}
```

続きを読むときは結果の `nextRead` を files 配列に入れる。

```json
{"files":[{"path":"src/App.java","startLine":1001,"endLine":2000,"expectedVersion":"<返却されたSHA-256>","offset":0,"charset":null}]}
```

version mismatch は自動的に新ファイルへ旧位置を適用せずエラーになる。最初の検索 / 読み取りからやり直す。grep の `nextRead` は省略位置からの source 読み取りであり、検索結果ページの cursor ではない。省略した別ファイルの検索には `baseDir` / glob を絞って再検索する。

Project 外の絶対パス、path traversal、sensitive path、途中の symbolic link を拒否する。これは以前の無制限な絶対パス読み取りからの意図した制約変更。Project 内の絶対パスと従来の3引数 ReadFileRequest /4引数 GrepMatch /既存 result constructors は維持する。

## 検証記録

変更前の関連テストを実行し成功を確認した (`target/file-operations-baseline.log`)。オフラインで古い workspace Maven cache を指定した初回実行は parent POM が見つからず、測定には含めていない。設定済み Maven repository を使う実行は成功した。

追加した2つの不具合再現テストは変更前に失敗した。同サイズ・同 mtime の外部変更が古い検索結果になることを確認した。総行数 fixture は最初の版で grep 自体の上限にも達していたため、全ファイルに1ヒットずつ置く fixture に修正した。読み取り抽象で2条件検索＋本文取得が1ファイル1回になることを検証する。

Phase ごとの計測値と最終回帰結果は実行後に追記する。実 LLM の token / task 成功率 /最大メモリなど未測定項目を推測で補わない。

固定 benchmark は `FileOperationsBenchmarkTest`。同じ main の detached checkout に同じテストを追加して実行した。fixture は150行 / 1,197 bytes、2条件と各前後5行、10回、各回新しい Tools、git inventory の代わりに固定 inventory を使用する。JFR の `jdk.FileRead` (threshold 0) の対象ファイルイベントだけを集計した。イベント数は EOF の read を含み、file open 数ではない。

| 固定ケース (10回合計、中央値のみ1回あたり) | Baseline | Phase 1 |
| --- | ---: | ---: |
| JFR FileRead イベント | 60 | 10 |
| 読み取り bytes | 35,910 | 11,970 |
| 返却 JSON bytes | 5,320 | 6,250 |
| 検索＋本文取得中央値 ns | 1,450,100 | 7,536,900 |

読み取り byte 数は 66.7%減。小さなファイルではパス・属性・hash 検証の費用が元の読み取り費用を上回った。返却 JSON は version / continuation / metadata の追加により17.5%増。初回計測の wall time は JVM / filesystem / JFR に依存し、タスク全体の速度や token 削減の証明ではない。追加 fixture による読み取り抽象の count は2条件検索＋本文取得で1回、並列20回の同一 snapshot 取得でも1回。実 LLM token、タスク成功率、編集失敗率、最大メモリ、全タスク時間は未測定。

Phase 1 の追加テスト11件と既存 Tools テスト111件は failure / error / skipped 0 を確認した。検索の query error を黙って落とす旧挙動は変更し、成功結果に続いて明示した error result を返す。grep の path / version メタデータも予算から差し引き、file errors は全 query 合計32件と omittedErrors を返す。

## Phase 2: 既存 Change Set の部分編集

新規 Tool は追加せず `proposeTextChangeSet` の Request を拡張した。従来の `{path,expectedText,replacement}` と次の部分編集を選択できる。両方式の併用は拒否する。

```json
{"request":{"path":"src/App.java","baseVersion":"<readMultiFile の version>","edits":[{"oldText":"int timeout = 30;","newText":"int timeout = 60;"},{"oldText":"return null;","newText":"return result;"}]}}
```

`baseVersion` は裸の SHA-256 hex または `sha256:` prefix 付き。全 hunk は同じ元版に照合する。oldText は空でなく一意に一致しなければならない。範囲の重複を拒否し、入力順で前の変更が後の一致判定を変えることはない。newText は空文字列も可能。最大64 hunk、old/new text 合計64 KiB、元と提案も既存の各64 KiBまで。UTF-8、BOM、CRLF/LF、末尾改行を文字列の正確な置換で保持する。

検証後は既存の永続化、Project 所有権、proposal hash、状態遷移、Apply/Discard を使う。保存形式は変更前後の全文を保持する既存形式なので旧保存データの移行は不要。外部変更を Apply 直前にも検証し、APPLIED receipt を再取得しても writer を再実行しない。既存の LOCAL_WRITE と SubAgent の境界を維持する。部分編集入力は承認を作らず、ToolPermissionGuard による拒否は従来どおり callback 前に発生する。

単一ファイルの Tool writer は `TextDocumentTransaction` の既存 staging / permission copy / atomic move を再利用する。UTF-8 bytes を同じディレクトリの一時ファイルへ書き、force、権限コピー、baseline と stage の再検証を行ってから置換する。atomic move 非対応時は非 atomic 書き込みへ fallback せずエラー。通常の move 失敗では target を変更しない。変更された一時ファイルは削除せず、cleanup エラーを元の例外に付ける。OS の rename は外部 writer に対する content compare-and-swap ではなく、最終検証と置換間の非協調な外部書き込みを完全に排除する保証はない。複数ファイルの完全な atomic transaction は保証せず、既存の journal / rollback / UNKNOWN フローを利用する。

差分表示は既存の正確な replacement renderer の前後にある同一行を省略し、変更範囲の前後3行を残す。離れた変更間の同一行は表示するので、常に最小 multi-hunk diff になる保証はない。プレビューは実行可能 patch ではない。`exportProposal` は完全な提案内容を保持し、Apply は省略されたプレビューから内容を復元しない。

新しい入力と差分表示の Red を確認後 Green / Refactor を実施した。`MultiHunkChangeSetTest` の9件が成功し、既存単一・複数ファイル Change Set / Tool / DiagnosedRepair の回帰も成功した。

### 小規模編集の固定ケース

500行 / 8,391 bytes の fixture で timeout の1行を変更。3回、既存の Read → Propose → Apply Tool 経路を使う。baseline は全文読み取りと全文入力、Phase 2 は1行＋version 読み取りと partial input。JFR は target と owned staging の read/write だけを集計し、SQLite native I/O は含めない。JSON はオフラインの同じ Jackson 設定で計測し、実 LLM token と区別する。

| 3回合計（中央値のみ1タスクあたり） | Baseline | Phase 2 |
| --- | ---: | ---: |
| Tool 呼び出し | 9 | 9 |
| 入力 JSON bytes | 53,994 | 1,128 |
| 出力 JSON bytes | 143,142 | 5,874 |
| 読み取り bytes | 151,038 | 226,557 |
| 書き込み bytes | 25,173 | 25,173 |
| 中央値 ns | 76,567,800 | 101,419,200 |

入力 JSON は97.9%減、出力 JSON は95.9%減。実 LLM token 削減率ではない。安全な staging / 再検証によってこのケースの読み取りは50%増、中央値は32.5%増。書き込みは部分入力でもファイル全体の atomic replacement なので byte 数は減っていない。3ケースすべてで保存 receipt と変更後の内容を検証したが、実 LLM のタスク成功率・失敗率は未測定。

## Phase 3: 検索順位と結果予算

候補収集は各 query/file の先頭4 hit を上限とし、先頭ファイルの大量 hit で後続ファイルを隠さない。未走査 hit があり得るときは truncated を返す。順位は明示 preferredPaths +10000、AST 宣言名完全一致 +800、ファイル名 stem 完全一致 +450 / 部分一致 +150、path +70、body 完全一致 +40 / その他 +10、複数 hit +3（最大16 hit）、Working Set +30。同点は path 昇順。正規表現も名前順位の signal は pattern 自体の文字列であり、意味的関連性の保証ではない。Java AST enrichment は preliminary 順の最大32ファイルだけ。同じ snapshot と既存 Javac parser / hash cache を再利用し、制限・解析失敗は matchedBy に表示する。

追加 request は maxBytes（256–65536、既定65536）、maxTokens（256–32000、既定8000）、maxLines（1–5000）、preferredPaths（最大20）。順位決定後、最大8ファイルに均等な初期予算を配分する。JSON metadata / escaping を含む byte と conservative token 推定の両方で縮小し、可能な限り複数候補を残す。次の読み取りは version-bound nextRead、削除した visible match の数は omittedMatches。未走査 hit の総数ではない。予算に metadata すら入らない場合は明示エラー。推定は tool 出力の選別だけに使い、ModelCallBudget の実 usage に加算しない。既存 RawToolResultStore / Compressor 経路は継続する。

TDD: ranking API 未実装の Red、順位・ノイズ・日本語巨大行・whole JSON 予算・同点・Working Set の Green、Project 外一覧取得の Red と共通パス検証による Green。欠落ディレクトリは既存の空一覧契約を維持する。関連 Repository Map / persistent index / Compressor 回帰も検証。

## Phase 4: Java シンボルから読む

既存 readMultiFile に symbol、includeBody（既定 true）、includeJavadoc（既定 true）、contextLines（0–25、既定0）、includeOwnerOverview（既定 false）を追加した。path は symbol 指定時に省略可能。全体探索は既存 Repository Map の Git inventory を使い、候補の source は request-local snapshots を共有する。コンパイラ、parse error、source 128KiB / inventory 1024 / aggregate 8MiB の上限や、途中の欠落ファイルは明示エラー。部分 index から唯一の候補を推測しない。path を指定して探索範囲を狭められる。

```json
{"files":[{"symbol":"dev.example.Client#send(String,int)","includeBody":false,"includeJavadoc":true}]}
```

既存 JavacTask / Trees の source positions から半開 UTF-16 startOffset/endOffset と1始まり開始・終了行を取り、package、class / interface / enum / record / annotation、constructor、method、field を索引する。ID は所有型の名前、`#`、メソッド名、ソース上の引数型（空白除去、varargs は AST の配列形）で構成する。constructor は `p.C#C()`、field は `p.C#field`、型は `p.C.Nested`、package は `p`。意味的型解決・型別名解決は行わない。オーバーロードの引数を省略すると最大20候補と Ambiguous エラーを返す。exact ID と path で選び直す。候補自体も予算で省略され得るので truncated を確認する。

署名は annotation / 複数行 declaration を保持し、method body や field 初期値を含めない。Javadoc は宣言の直前の comment だけを関連付ける。所有型概要は最大4署名・各256文字。文脈指定は周囲の行を含むため、includeBody=false でも文脈の行に本文が含まれ得る。結果の source は表示行であり、BOM・改行を完全保存する編集 baseline は readTextChangeSetBase を使う。

同じ SHA-256 の source と AST を結び付ける。optional SQLite metadata profile を v2 に更新し、古い offsets 不在 index は再解析する。Repository Map の本文非公開契約は既存回帰テストで維持する。署名の最大長2048文字、source宣言最大64個など既存解析上限は残る。

巨大宣言は JSON 全体の byte / conservative token 予算（64KiB / 8000、batch共有）と行上限で打ち切る。nextRead をそのまま渡す。symbolOffset は選択した declaration＋指定文脈の開始からの UTF-16 offset で、通常行読みの offset と区別する。version が変われば続きは拒否する。symbol と line ranges / charset の混在も拒否する。

index 処理は symbol request ごと10秒を上限とし、file 間・解析後に中断 / deadline を確認する。クラス header の raw Unicode escapes は Java の前処理で delimiter が変わるため、signature-only read は明示エラー。本文付き read は可能。未確定 header を宣言全体の signature に置き換えず、Repository Map から本文が漏れない。最終レビューで escaped 外側 delimiter と通常の method brace の混在を Red にして修正した。

TDD: 新 API / metadata 不在の Red → generic overload、constructor / field / nested class / package、Javadoc / annotation / signature、BOM / CRLF、version更新と巨大メソッド継続の Green。さらに全体同名候補、shared snapshot の実読取回数、record / enum / interface / annotation、compiler不在 / oversized / outside、実 Tool callback JSON を確認した。既存 Map が field 初期値を返してしまう回帰を検出して修正し、既存テストを変更せず再検証した。

## 固定計測の最終比較

raw JSON は [file-operations-benchmark](file-operations-benchmark) に保存した。各値は同じ source fixture / 同じ JFR filter / 同じ Jackson 設定。探索結果に version / 順位 / 継続情報を加えた分、小さい検索ケースの JSON は増えている。中央値は小サンプルのローカル時間で、速度改善の保証ではない。

| 2-pattern search（10回） | Baseline | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
| --- | ---: | ---: | ---: | ---: | ---: |
| source read bytes | 35,910 | 11,970 | 11,970 | 11,970 | 11,970 |
| JFR FileRead events | 60 | 10 | 10 | 10 | 10 |
| returned JSON bytes | 5,320 | 6,250 | 6,250 | 6,980 | 6,980 |
| median ms | 1.4501 | 7.5369 | 6.3457 | 6.5566 | 8.6482 |

| 小規模編集（3回） | Baseline | Phase 2 | Phase 3 | Phase 4 |
| --- | ---: | ---: | ---: | ---: |
| Tool calls | 9 | 9 | 9 | 9 |
| input JSON bytes | 53,994 | 1,128 | 1,128 | 1,128 |
| output JSON bytes | 143,142 | 5,874 | 5,874 | 6,021 |
| source/stage read bytes | 151,038 | 226,557 | 226,557 | 226,557 |
| source/stage write bytes | 25,173 | 25,173 | 25,173 | 25,173 |
| median ms | 76.5678 | 101.4192 | 108.6972 | 107.7265 |

Phase 1 の編集ケースは未測定。最終編集入力97.9%減 / 出力95.8%減に対し、I/O再検証は読み取り50%増、中央値は40.7%増。

| Java method 読み取り（5回） | Baseline（既知 path 全文） | Phase 4（既知 path + symbol） |
| --- | ---: | ---: |
| Tool calls | 5 | 5 |
| source read bytes | 52,930 | 52,930 |
| returned JSON bytes | 58,505 | 2,915 |
| median ms | 0.3749 | 229.4343 |

500行の周辺 comment と4行の method を含む同じ UTF-8 source を使った。出力 JSON は95.0%減。毎回新しい Tools / AST cache で測る cold case なので、Javac 初期化・解析による時間増加が大きい。warm cache 時間は未測定。source hash の鮮度確認に全文 bytes が必要なので読み取り byte 数は減っていない。

実 LLM 入出力 token / 実タスク成功率・失敗率 / heap peak / syscall open 数 / inventory走査回数は未測定。各ケースの返却行と内容、編集3回の Apply receipt / 最終保存内容はテストで検証した。token budget の推定は上限検証用であり、この table の JSON byte 削減率を実 token 削減率に読み替えない。

再測定は `./mvnw -Pfull -Dtest=FileOperationsBenchmarkTest test`。baseline に同じ benchmark test をコピーして実行し、read/search の旧 API と部分編集・symbol API の追加有無を reflection で選ぶ。JFR は EOF event を含み、FileRead events は open 数ではない。SQLite native I/O は未計測。

## 全体回帰で見つかった既存テストの修正

最初の full run は AttentionDeliveryTest と SubAgentEventTest が失敗した。どちらも変更前 `dc833e53` の同じテストで再現した。前者は端末 REI_API_KEY と injected 固定 test key の不一致で immutable properties の再 bind が失敗したため、test fixture に同じ `rei.api-key=secret` を明示した。認証 / Project owner / 明示配送の assertions は残した。後者は現行 Shell の answer 中通知遅延と古い即時表示 assertion の不一致。通知は完了後に検証し、全ての run prefix / 内容 assertion を維持し、answer 中に child 通知が出ないことと parent text が連続する assertion を追加した。recent-event 表示は answer 完了後に確認する。Production Shell / Attention コードは変更していない。

署名境界の最終修正後、別出力先の `verify -Pintegration` は全 Java suite と Spring Boot package まで exit 0。Java は3,804件、failure 0 / error 0 / skip 1（3,803成功）。既存 DocumentRendererProcessTest の real PlantUML case は `rei.test.plantuml.jar` 未設定の assumption skip。live profile は既存設定で除外。新テストの skip / disable は追加していない。Client は25 files / 99 tests成功、typecheck＋Vite build＋ESLint成功。Native は120 tests成功、`cargo check --locked --features native` 成功。UI 変更がないため browser E2E / 実 Native window E2E は実行していない。

通常 target の Spring Boot repackage は既存 JAR rename 失敗で終了した。実行中の Rei JAR process が存在するため停止・削除せず、検証専用 POM で build.directory / finalName だけを一時的に変更して別出力先の verify を行った。source / dependencies / compiler / plugin 設定は同じで、最終 source の全テストと再コンパイル、package を完了した。一時 POM は削除した。成功 artifact は `target/file-operations-package/rei-file-operations-verify.jar`、ログは `target/file-operations-isolated-verify.log`。通常 target の verify 全体を成功と読み替えない。Java 専用 lint plugin は既存 POM にないため compile、path / atomic Apply / schema / bounds の差分レビューと `git diff --check` を実施した。
