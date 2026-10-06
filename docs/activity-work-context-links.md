# Activity / Work Context 保存参照

`/activity context [today|yesterday|YYYY-MM-DD]` は選択Projectについて、保存済みActivity標本とWork Contextの出典参照を照合する。読み取り専用で、追加撮影・LLM・Gitコマンド・Tool実行・Work Context更新は行わない。

Activityの既存RecentEventはSHELL/FILE_EDITに分類したTool完了イベントを最大16件・120秒だけ保持する。この短い証跡へEvent ID、Session ID、Turn ID、Run IDを追加し、標本の既存JSONへ保存する。コマンド本文、Tool結果、ファイル本文、会話はコピーしない。旧JSONにIDがない場合も読み込み可能だが、新たにIDを推測しない。

選択Projectの直近100個のimmutable Work Context revisionsを読み、標本に保存した近接イベントと、Work Context ItemのTOOL出典のEvent ID・Session ID・Run ID・イベント時刻が一致する場合だけリンクを返す。Project IDも一致させる。同じ標本/イベントの組は一度だけ、最大128参照、各参照のItem IDは最大20個。複数revisionに同じ参照が残る場合、検索できた範囲の最も古いrevisionを使う。

表示: 標本ID/観測時刻、Event ID/イベント時刻/種類、Session/Turn/Run ID、Work Context revision/Item ID、保存Git snapshotのbranch/commit/取得時刻。Work Contextの文章、コマンド、結果、ファイル・directoryパスは表示・コピーしない。日付はActivityのzoneで解決し、現在日は固定した現在時刻まで、未来日は拒否する。

これはAgent実行の近接参照で、foregroundアプリやユーザーがそのタスクに従事した証明ではない。GitはWork Contextに保存された取得時刻の情報で、標本時点のbranchを保証しない。内容の類似やProject名だけで結び付けない。

Work Context未生成、出典未採用、直近100 revisionsより古いだけの出典、旧Activityに参照IDがない場合は一致なし。保存証跡が後でWork Contextに採用されれば、その後の読取時にリンクできる。自動backfill・誤った完了判定・長期記憶昇格は行わない。

## 観測時の作業文脈保存

`rei.activity.work-context-enabled=true` を明示設定すると、Activityの既存観測時に選択Projectの現在Work ContextとGit branch/commitを取得し、標本のJSONへ保存する。既定はfalse。Activity本体のenabled/pause・除外ウィンドウ・保持規則を維持する。追加LLM・撮影・Tool実行・Work Context更新は行わない。Gitは既存WorkContextGitの読み取り専用処理（各問い合わせ2秒、合計最大4秒）を再利用し、失敗時はunknownとして古いGit情報へ置き換えない。

保存するのはProject ID、観測時刻、Work Context revision/更新時刻、Git取得時刻・branch/commit、最大20の現行Item ID/kind/status/certaintyと各最大8のTOOL出典参照。上限超過はpartial=true。出典はEvent/Session/Turn/Run/Tool call ID・取得元の観測時刻とProject内の相対ファイル名で、コマンド本文・結果・作業文・会話・絶対directoryはコピーしない。Project外パス、秘密用directory/.env/鍵ファイル等の名前は除外する。Tool call IDは保存Event APIから元コマンドを確認するための参照であり、コマンド内容を推測しない。

`/activity context` はこの保存文脈を `OBSERVATION_CONTEXT` としてforeground process・Project・Git・Item・出典参照とともに表示する。Work Context履歴が取得できなくなる前に保存済みなら、その後も標本から読める。Work Context未生成ならrevision=0、ItemなしでGitのみ保存する。既存の近接Event照合は `EVENT_REFERENCE` として維持し、そのGitは引き続き古いWork Context snapshot時点の情報である。読取コマンド自体はGitを再実行しない。

観測前後で選択Project/rootが変わる、別ProjectのWork Context、未来時刻の文脈は採用しない。保存文脈と最終選択Projectの異なるsource寄与も破棄する。旧JSONのworkContext欠落はnullとして読め、後から観測時Gitを捏造しない。

foregroundはOSの観測、Projectはアプリの選択状態、Itemは保存Work Contextの申告である。この関連はユーザーがそのタスクに従事した証明や作業成果の判定ではない。Git・OS・DBを跨ぐ原子的snapshotでもない。foregroundとtaskの意味的帰属、独立Task ID、専用Web/Native UIは追加候補として残る。
