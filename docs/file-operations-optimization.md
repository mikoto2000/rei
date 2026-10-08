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
