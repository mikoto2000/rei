# Artifact Delivery

`rei.web.enabled=true` と `rei.artifacts.enabled=true` で有効になる。既定OFF。
既存データを移動せず、共通SQLiteへ `rei_artifacts` テーブルを追加し、
`rei.data-dir/artifacts` にUUID名の不変コピーを保存する。

## 取得と保存

Bearer認証済みの `/api/v1/artifacts` は登録Projectを横断して一覧取得する。
`projectId`、`sessionId`、`runId`、`limit`（1–100）、`cursor` で絞り込める。
`/{artifactId}` と `/{artifactId}/content` は `projectId` と所有 `sessionId` が必要。
SessionのないRunではSessionパラメーターを省略する。別Project/root/Sessionは404。
任意ファイルパス、元ファイルの絶対パス、保存ディレクトリはAPIへ渡せない。

内容取得はサイズとSHA-256を毎回照合し、attachmentのContent-Disposition、
正しいContent-Type、nosniff、no-store、ETagを返す。最大32MiBの内容を一括検証するため
Rangeは非対応（416、Accept-Ranges: none）。部分取得でハッシュ検証を省略しない。

NativeのArtifact画面はサーバー保存済み一覧を取得し、TaskのArtifact結果やRunから開ける。
テキストはHTMLとして実行せず表示する。PNG/JPEGのpreviewは2MiB、最大辺8192、
最大16Mi画素。画像読込失敗は画面に表示する。PDFなどは保存操作で受け取る。
明示保存先はOSのDownloads/Rei/Artifact UUID/filename。同じハッシュの既存ファイルは
再利用し、別内容は上書きしない。フロントエンドは保存先を指定できない。

## 既存成果物

Image生成は所有Run内で生成したPNGをコピーし、Run結果へArtifact IDを返す。
元ファイルを後から変更してもコピーは変わらない。Task結果には所有Session/Runの参照を投影する。

`POST /api/v1/artifacts/export` は既存の保存済み成果物を登録Sessionへ明示コピーする。
JSONは `projectId`、`sessionId`、`sourceKind`、`sourceId`、任意の `version`。
`sourceId` は保存済みUUIDで、パスは受け付けない。

| sourceKind | 内容 | version |
| --- | --- | --- |
| CHANGE_SET_PROPOSAL | 保存済み単一ファイル変更提案のUTF-8本文 | 省略 |
| PAPER_ORIGINAL | 保存済みPDF | 省略 |
| PAPER_EXTRACTED | 保存済み抽出JSON | 省略 |
| PAPER_SUMMARY | 保存済み要約JSON | 正確なcache keyが必須 |
| PAPER_TRANSLATION | 保存済み翻訳JSON | 正確なcache keyが必須 |

exportはモデル呼出し、PDFダウンロード、提案Applyを行わない。存在しない版は失敗する。
Paper Libraryは共有資料源であり、明示exportしたコピーだけが対象Project/Sessionの所有物になる。
同一sourceの再要求は同一ハッシュだけを再利用し、内容変更を自動上書きしない。

## 保存上限と復旧

`rei.artifacts.max-bytes` は既定32MiB（上限32MiB）、`max-total-bytes` は512MiB
（上限1GiB）、`max-artifacts` は1024（上限10000）、`retention` は30d（1m–90d）。
削除済みを含むmetadataは10000件で受付を止める。容量超過は507。
期限切れ、missing、stale、deletedは一覧に残る。期限切れ/削除は410、改変は409。
期限切れのファイルは自動削除しない。明示DELETE（所有Project/Session body）で
コピーだけを削除し、tombstoneを保持して容量を解放する。元成果物は削除しない。

公開前にPUBLISHINGを永続化し、一時ファイルをatomic moveした後にAVAILABLEへ遷移する。
プロセスPIDと開始時刻が失われた途中公開/削除はUNKNOWNにする。
同一sourceの自動再生成、未確定コピーの内容配信はしない。別JVMを公開前/移動後で
強制終了する試験で、この境界を検証する。UNKNOWNは内容を配信せず明示削除できる。
