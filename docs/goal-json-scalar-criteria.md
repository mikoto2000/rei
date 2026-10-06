# JSON scalarを含むGoal完了条件

既存 `/goal create --criteria-json` に、Project内のUTF-8 JSONファイルを固定JSON Pointerで読む型付きscalar条件を追加した。最大16件の全条件一致で、SHA-256条件と混在できる。同じファイルの異なるPointerは別条件になり、同じPointerの重複は拒否する。例:

```json
[
  {"relativeFile":"report.json","jsonPointer":"/ready","expectedJson":"true"},
  {"relativeFile":"report.json","jsonPointer":"/count","expectedJson":"1"},
  {"relativeFile":"report.json","jsonPointer":"/status","expectedJson":"\"complete\""}
]
```

`expectedJson` はscalarのJSON表現を格納した文字列。boolean／number／string／nullの型を保持し、数値はBigDecimalの値で照合する。nullと未存在は区別し、`~0`／`~1`とarray indexを含むPointerに対応する。Pointerは256文字・深さ16、expectedJsonは1024文字・数値64桁まで。object／array・任意code・正規表現を条件にしない。SHA-256とJSONを1条件に併記できない。

JSON成果物は最大64 KiB・深さ32、strict UTF-8／JSONを要求する。重複キー・末尾JSON・不正文字を拒否する。既存Project root／相対path／symlink／regular file境界と取消を維持し、成果物本文を診断へ返さない。内容不一致・未存在は既存Run予算内で継続し、不正JSON／過大／所有者異常はBLOCKEDになる。ホスト独立検証を使い、モデルの完了声明だけで成功にしない。

SQLiteの既存criteriaテーブルへnullable列を追加し、旧SHA条件・Goal予算・履歴を保持する。条件は再起動後も復元する。JSONを含む成功理由は`criteria_verified`、旧SHAのみは`file_digest_verified`を維持する。既に一致したGoalはRun／LLMを増やさず完了する。

Planning prompt／既存HTTP確認・Native確認表示／Reflectionにも条件を渡す。Reflectionの既存`expectedSha256`フィールドは、JSONを含むGoalでは条件全文のJSONを保持し、旧SHAのみの表現を維持する。作成入口は既存Shellで、HTTP／Nativeは既存の一覧・Verify・Run・復旧に接続する。任意の汎用外部条件や意味的成果保証は別の候補。
