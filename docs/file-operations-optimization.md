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
