# README から移した実装補足

利用手順は [README](../../README.md)、開発環境は [DEVELOP](../../DEVELOP.md) を参照してください。
この文書は、README に混在していた内部仕様と変更記録を保管するものです。

## 関連する設計・実装報告

- [プロジェクト別保存と移行](../../docs/agent-run-project-state.md)
- [プロジェクトをまたぐ同時実行](../../docs/implementation-report-active-runs.md)
- [バックグラウンドコマンド](../../docs/implementation-report-background-commands.md)
- [履歴コマンド](../../docs/implementation-report-history-shell.md)
- [履歴検索](../../docs/implementation-report-history-retrieval.md)
- [Computer Use の設計・検証](../../docs/vision-computer-use.md)
- [ShowUI・UI-TARS の位置特定と診断](../../docs/computer-use-showui.md)

## 履歴保存と検索

プロジェクトに所属する会話の追記専用ログは `<rei-data-dir>/projects/<ProjectId>/conversations/yyyy-MM-dd.jsonl` に保存されます。同じ `chat:main` でも ProjectId が異なれば別会話です。短期会話メモリの件数上限とは独立して保持します。

会話履歴検索Toolの標準は `CURRENT_PROJECT_PREFERRED` です。実行元の履歴を優先し、関連度・件数が不十分な場合だけ他プロジェクトも検索します。`CURRENT_PROJECT_ONLY` は実行元限定、`ALL_PROJECTS` は全登録プロジェクトを関連度順で検索します。会話種別を指定する `scope`（chat / tool 等）とは別の `retrievalScope` 引数です。

ユーザーが別プロジェクトを指定した場合は、`referencedProject` に登録名を渡すと、そのプロジェクトを実行元より優先できます。曖昧な名前・存在しない名前は解決しません。結果には出典の ProjectId・Project名と境界情報が含まれ、他プロジェクトのパスやbuild commandを現在の環境に自動適用しません。

検索結果は実行元最大8件、他プロジェクト合計最大3件、本文各500文字です。他プロジェクトの詳細取得も最大3件・本文各500文字に制限します。Run中に `/project cd` しても、検索の実行元はそのRunのProjectIdのままです。検索状況は `[history.search]` 通知で確認できます。保存データを統合したり、検索結果をGlobal Memoryへ昇格したりする処理はありません。

## 出力上限と再計画

LLM 応答の `finish_reason` が `length` の場合は正常完了扱いせず、`output-limit-planner` の LLM に元のゴールをサブゴールへ再計画させます。
再計画後は各サブゴールを通常チャットと同じ実行経路で順次処理し、最後にサブゴール結果を統合して元の要求への最終回答を作ります。
`output-limit` の各値は、再計画ループを防ぐための決定論的な上限です。

## 画面操作の位置特定と検証

UI-TARS モードは公式の位置特定専用プロンプトで1点を要求します。`(281,659)` は `[0.281,0.659]` として扱い、送信画像のピクセル座標とは解釈しません。出力上限は128トークン、画像縮小と失敗時の停止は ShowUI モードと同じです。診断有効時は `uitars-input.png`、`uitars-request.json`、`uitars-response.json`、通信例外時には `uitars-error.txt` を保存します。

ShowUI モードでは、判断モデルに画像対応モデルが必要です。ShowUI には選択した画面1枚と短い対象説明を渡し、`[x, y]` の割合座標を返してもらいます。Rei が元の画像座標とディスプレイ配置に変換して操作します。クリック以外の操作と完了確認は判断モデルが担当します。

`showui` / `uitars` はともに2段階で位置を特定します。最初に選択画面全体から位置を推定し、その位置の周辺を元画像から縦横1/2の大きさで切り出して、同じ対象を再推定します。実際にクリックするのは2回目の座標を元画面へ変換した位置だけです。2回目が失敗した場合は1回目の座標を使わず停止します。各クリックの位置特定にモデル呼び出しが2回必要です。

診断には従来の `showui-*` / `uitars-*` に加え、`showui-refinement-*` / `uitars-refinement-*` として切り出し画像・要求・応答・通信例外を保存します。再推定の request JSON には元画像上の切り出し範囲も記録します。

両モードでは `computer-use-planner` の判断モデルで検証します。再推定前に切り出し画像内で対象を識別できることを確認します。再推定後は、目標・履歴を渡さずに赤い目印の中心にある要素の種別とラベルを説明させます。その後、画像を渡さない別の要求で、観察結果と目標の種別・同一性を照合します。「ボタン」と「入力欄」のように種別が不一致なら、承認されてもコード側で拒否します。拒否・曖昧な応答・解析エラーでは `MODEL_ERROR` で停止します。成功時は判断1回・位置特定2回・切り出し検証1回・目標を伏せた要素識別1回・照合1回の計6回のモデル呼び出しです。モデルによる誤認を完全に防ぐ保証はありません。

検証の診断ファイルは `crop-verification-*`、`point-description-*`、`point-verification-*` です。要求・応答・通信例外に加え、前2段階は画像も保存します。目印付き全画面画像は `point-description-input.png`、元画像から切り出した候補点周辺の拡大画像は `point-description-detail.png`、切り出し範囲は `point-description-geometry.json`、目標との照合は `point-verification-response.txt` で確認できます。赤い目印は検証用コピーだけに描画し、実際のデスクトップや位置特定用画像は変更しません。

送信画像は縦横比を保って1枚あたり約105万画素以下に縮小します。ShowUI に履歴や独自の操作 JSON スキーマは送らず、出力上限は128トークンに固定します。ただし、サーバー側の画像処理によって入力トークン数は変わるため、4096トークンのコンテキストに必ず収まる保証はありません。

専用接続先での通信失敗や不正な ShowUI 座標応答は、別モデルや判断モデルの推測座標に切り替えず、Computer Use タスクを失敗させます。解析レスポンスと例外をログに記録するため、ログには画面由来の内容が含まれることがあります。

## OPML の取り込み

相対パスは現在の project を基準に解決し、`~/` はホームディレクトリへ展開します。ファイルは最大10 MiB、XML の深さは128要素までです。DOCTYPE・外部 Entity・外部 DTD・XInclude は無効です。カテゴリ階層と `htmlUrl` は解析のみで永続化しません。登録中に記事取得は行わず、既存の定期更新または `/feed update` で取得します。コマンドの終了コードは処理完了（部分失敗を含む）が0、ファイル単位のエラーが1、引数不足が2です。

## ベクトルストア

検索には `sqlite-vec` を使います。埋め込みは `vec0` 仮想テーブルに保持し、KNN 検索に lexical prefilter と軽い rerank を組み合わせています。`source` / `docId` の絞り込みも検索時に適用されます。

登録時は `docId` / `source` / `chunkIndex` を必須 metadata として扱い、欠損している文書はエラーにします。検索時に embedding 次元が一致しない場合もエラーにします。`replaceBySource` は source 単位の delete + insert を 1 トランザクションで実行し、途中失敗時はロールバックされます。文書一覧や削除も `document_chunks_vec` の集約で処理します。

`similarityThresholdAll()` を使っても score が 0 以下の結果は返しません。現在の実装では「関連性がない候補を除外する」挙動を優先しています。SQLite ファイル破損時は破損として、ロック発生時はロックとして明示的に失敗させます。存在しない `docId` / `source` の削除は 0 件または `false` を返します。

## メモリ統合機能の更新内容

### 変更点

- `/memory consolidate` と `/memory summarize` で LLM 呼び出しに失敗した場合、異常終了せず `[error] ...` を表示します。
- `/memory consolidate` の競合判定にタイムアウト制御を追加しました。
- 競合判定がタイムアウトした候補は保存せず、以下の警告を表示します。
  - `[warn] 競合判定がタイムアウトしたためスキップしました`
- メモリ関連の DB 例外は `IllegalStateException` に変換し、ユーザー向けメッセージとして扱います。

### 関連設定

- `REI_MEMORY_CONFLICT_TIMEOUT_SECONDS`
  - デフォルト値: `60`
  - `/memory consolidate` の競合判定タイムアウト秒数として使用されます。

### 補足

- タイムアウトした候補は保存されません。
- `extractCandidates` / `summarize` で LLM エラーが発生した場合、`MemoryService.save()` は実行されません。
