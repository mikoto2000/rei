# Declarative completion/dependency predicates

`rei.predicates.enabled` / `REI_PREDICATES_ENABLED` は既定false。
有効にすると既存GoalとDependencyのJSON観測に共通DSLを利用できる。
任意コード、shell、JavaScript、SpEL、reflection、式からのnetwork/file操作は実行しない。
観測の取得は既存Project/root/session/permissionとbounded file/HTTP経路を使う。

```json
{"version":1,"expression":{"op":"AND","args":[
  {"op":"GTE","pointer":"/coverage","value":80},
  {"op":"MATCH","pointer":"/status","value":"passed|complete"}
]}}
```

演算子はAND/OR/NOT、EQ/NE、GT/GTE/LT/LTE、MATCH、EXISTSだけ。
AND/ORは1–8個、NOTは1個のargs。比較はpointerとscalar valueを持つ。
数値はBigDecimal、文字列は文字列順、boolean/nullはEQ/NEのみ。
型の暗黙変換はしない。MATCHは文字列全体のRE2/J正規表現照合。
EXISTSはpointerだけを持ち、観測済みの有効JSON内での存在を調べる（明示nullも存在）。
比較の異なる型、missing/non-scalar、無効JSON、期限切れ、取消しはUNKNOWN。
NOT UNKNOWNとNE missingもUNKNOWNで、観測がないことを成功にしない。
ANDの既知false/ORの既知trueは判定できるが、残りだけでUNKNOWNを逆転させない。

上限: DSL4096文字、32 nodes、depth8、1nodeの子8件、pointer256文字/16segments、
expected scalar1024文字、JSON body65536 bytes/depth32/数値64文字。
実際の文字列は4096文字、regex256文字/program size512。
RE2/J 1.8でbacktrackingを使わず、backreference/lookaround等は受け付けない。
compile時の展開増大を避けるためcounted repetitionの `{}` は許可しない。
DSL評価は250ms以内で期限を確認し、超過時はUNKNOWN。既存HTTP取得は2秒以内、redirectなし。
重複JSON keys、余分なfields、version違い、型不正、深すぎる式、code演算子は保存前に拒否する。

Goalの既存criteria JSONには `predicateJson` として上のDSLのJSON文字列を指定する。
同じcriterionにsha256/jsonPointer/expectedJsonを併用できない。
`agent_goal_criteria.predicate_json` nullable columnを既存DBへ追加し、旧SHA/scalar criteriaを保持する。
保存・再起動・既存Shell criteria作成・HTTP verify経路を利用する。
disabled時はverificationがpredicate_disabledとなり、Goal runはclaim/モデルdispatch前に拒否する。
Goalの完了reasonは既存criteria_verified、reflectionは検証済みcriteriaとして記録する。

Dependencyは `FILE_JSON_PREDICATE`（target=Project相対JSONファイル、expected=DSL JSON文字列）。
HTTPは既存 `HTTP_JSON_VALUE` のexpectedに `{ "status":200,"predicate":{...DSL...} }` を指定する。
旧 `{status,pointer,value}` は変更しない。network permission、Bearer API境界、watcherの明示enablementを維持する。
disabled/UNKNOWNはBLOCKED、不一致はWAITING、SATISFIEDのみCOMPLETED。
HTTPのdisabled確認はrequest前で、bodyやprivate valuesを保存receiptへ出さない。

SQLite再生成、local HTTP、authenticated Goal HTTPと既存scalar経路で検証する。
正規表現仕様は[RE2/J公式説明](https://github.com/google/re2j)、
採用版は[1.8 release](https://github.com/google/re2j/releases/tag/re2j-1.8)を参照。
