# Auto Sleep の保存済みセッション再検出

Auto Sleep 有効時、起動時 metadata snapshot の走査を終えると、`retry-interval` の間隔で SessionRepository の永続一覧を再走査する。新しい会話・terminal callback なしでも、稼働中に追加・更新された保存済みセッションを候補として再検出できる。

busy / minimum-idle / 任意 cron の既存ゲートを通った tick でのみ読む。1 tick につき最大100件の1ページ、候補保持は最大256件。ページ cursor を次の tick に持ち越し、末尾に達したら間隔を待って先頭から再検出する。既存の登録済み project と session の encoded project 境界を検証し、条件外 metadata から履歴を読まない。

読込失敗・壊れた metadata・上限違反はログへ例外クラスだけを出し、走査 cursor を戻して retry-interval 後まで再試行しない。通常候補の unsleptTurns / minimumTurns / 単一 worker / 取消・活動 version / Sleep の transaction とカーソルを再利用する。metadata 更新だけでモデルを呼ぶことはなく、idle と未整理 turn 件数を満たす必要がある。

監視方式は定期 polling。ファイルの変更時刻や OS watcher を使わず、別プロセスとの同時書込を許可するものでもない。FileSessionRepository は既存の JSON ファイル全体を読み、ページ境界を返すため、この変更の100件上限は候補処理件数でありファイル読込byte数の上限ではない。動的な一覧なので、走査中に更新された行は次の周期で検出される場合がある。アプリ終了時の追加 Sleep は行わない。
