# Activity / Work Context 保存参照

`/activity context [today|yesterday|YYYY-MM-DD]` は選択Projectについて、保存済みActivity標本とWork Contextの出典参照を照合する。読み取り専用で、追加撮影・LLM・Gitコマンド・Tool実行・Work Context更新は行わない。

Activityの既存RecentEventはSHELL/FILE_EDITに分類したTool完了イベントを最大16件・120秒だけ保持する。この短い証跡へEvent ID、Session ID、Turn ID、Run IDを追加し、標本の既存JSONへ保存する。コマンド本文、Tool結果、ファイル本文、会話はコピーしない。旧JSONにIDがない場合も読み込み可能だが、新たにIDを推測しない。

選択Projectの直近100個のimmutable Work Context revisionsを読み、標本に保存した近接イベントと、Work Context ItemのTOOL出典のEvent ID・Session ID・Run ID・イベント時刻が一致する場合だけリンクを返す。Project IDも一致させる。同じ標本/イベントの組は一度だけ、最大128参照、各参照のItem IDは最大20個。複数revisionに同じ参照が残る場合、検索できた範囲の最も古いrevisionを使う。

表示: 標本ID/観測時刻、Event ID/イベント時刻/種類、Session/Turn/Run ID、Work Context revision/Item ID、保存Git snapshotのbranch/commit/取得時刻。Work Contextの文章、コマンド、結果、ファイル・directoryパスは表示・コピーしない。日付はActivityのzoneで解決し、現在日は固定した現在時刻まで、未来日は拒否する。

これはAgent実行の近接参照で、foregroundアプリやユーザーがそのタスクに従事した証明ではない。GitはWork Contextに保存された取得時刻の情報で、標本時点のbranchを保証しない。内容の類似やProject名だけで結び付けない。

Work Context未生成、出典未採用、直近100 revisionsより古いだけの出典、旧Activityに参照IDがない場合は一致なし。保存証跡が後でWork Contextに採用されれば、その後の読取時にリンクできる。自動backfill・誤った完了判定・長期記憶昇格は行わない。

foregroundとtaskの意味的帰属、観測時点のGit/ファイル/terminal command相関、独立Task ID、専用Web/Native UIは今後の対象。
