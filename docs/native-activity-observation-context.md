# Native 保存観測時 Work Context

Activity分析画面内の「保存観測時文脈」で、登録Projectと対象日を選択し、「保存観測時の文脈を取得」を押す。画面表示や選択変更だけでは取得しない。空欄の日付は接続先Activity journalの今日を使用する。

観測時に保存された前景アプリ/Git/Work Context revision・状態・Tool出典を、専用Project限定HTTPから読む。Nativeは固定認証GETだけを呼び、schema/scope/選択Project/日付/zone/件数/出力上限を照合する。追加モデル・Run・観測・Git取得・Work Context更新はしない。

出典欠落件数とpartialを表示し、旧記録や不一致情報を現在文脈で補完しない。部分表示では件数が確認範囲だけであることを示す。reportはReactのテキストとして表示し、HTMLやcommand/file参照を実行しない。保存状態は観測時の申告であり、ユーザーのtask従事・成果の証明ではない。

Project/日付/接続先/登録一覧/lock/unmountの変更で結果を隔離し、遅延応答を無視する。取得失敗で自動再送・別操作・更新を行わない。別Projectや未知scopeの応答を選択Projectの記録として表示しない。

HTTPは保存OBSERVATION_CONTEXTの専用表示で、既存ShellのEVENT_REFERENCE履歴照合は従来経路に残る。画像・window title・コマンド本文/結果・task本文を取得しない。意味的task帰属はこのUIでは実装したとは扱わない。
