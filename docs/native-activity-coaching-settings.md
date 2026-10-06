# Native Activity Coaching 設定

Activity分析画面にCoaching設定を追加。画面を開くだけでは設定取得・観測・モデル・助言評価を開始しない。「保存済み設定を取得」で接続先の端末全体設定を読む。

分類/基準比率/最低観測時間/最低coverage/最大不明比率/助言間隔を編集し、「設定変更を確認」で保存済み基準と変更後を確認する。「無効状態で保存」で明示保存する。編集すると確認を取り消す。有効/無効化は別に保存済み基準を確認して操作し、未保存draftを有効化したことにはしない。

Nativeは固定された認証済みCoaching HTTP APIだけを使用する。必須flag・基準・safe integer revisionを事前検証し、response schema/scope/criteria/revisionと保存した値のechoを照合する。POSTに確認済みrevisionを渡し、server側のSQLite CASを再利用する。未知scope・不正基準・schema不一致・誤った保存応答を成功として表示しない。

失敗・結果不明時は編集状態を外し、保存状態の再取得を求める。自動再送しない。処理中の重複クリックを防ぎ、接続先切替/vault lock/unmount後の応答を無視する。古い接続先の確認を新しい接続先へ流用しない。

設定変更で助言receiptやcooldownをリセットしない。追加モデル、Run、観測取得、助言配信の呼出はない。助言の意味的個人化・Activity/Work Context専用詳細UIはこの設定画面では実装したとは扱わない。
