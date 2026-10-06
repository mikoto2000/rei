# HTTP JSON値の依存条件

既存registerDependencyでHTTP_JSON_VALUEを指定する。targetは既存条件と同じHTTP/HTTPS URL、expectedはstatus・JSON Pointer・期待するscalarの3項目を持つJSON文字列。

```json
{
  "kind": "HTTP_JSON_VALUE",
  "target": "https://example.invalid/jobs/1",
  "expected": "{\"status\":200,\"pointer\":\"/jobs/0/ready\",\"value\":true}",
  "lifetime": "PT1H"
}
```

statusは整数100..599、pointerは`/`から始まる256文字・16segment以内。`~0`／`~1`のescapeと配列の添字を使える。全体root指定・wildcard／式評価／code実行は行わない。valueはstring・number・boolean・null、JSON表現1024文字以内。expected全体4096文字以内。未知の項目・重複キー・末尾の別JSONを拒否する。

status一致と、指定値の型を守った一致でCOMPLETED／http_json_value_verified。不一致・欠落・指定値がobject/arrayはWAITING／http_json_value_mismatch。nullは欠落と区別し、boolean/stringの暗黙変換はしない。数値はBigDecimalの数学的比較で1と1.0を一致とし、0.1と0.10000000000000001を区別する。

本文は最大64KiBをrequest内で一時的に解析する。本文・実際の値・parser診断は返却／SQLite保存しない。期待条件は登録内容としてSQLiteへ保存する。gzip展開は追加しない。JSON最大深さ32・数値literal64文字・string65536文字。重複キー・trailing data・不正JSON・解析上限ではWAITING／http_json_invalid。本文超過はBLOCKED／http_body_too_large、途中切断・timeout等はWAITING／http_unavailable。status不一致はWAITING／http_status_mismatch。旧custom HTTP portの未対応はBLOCKED／http_json_verification_unsupported。

既存clientの2秒・no redirect・request cancelを使う。checkHttpDependency／waitForHttpDependency・Shell network guard・明示有効なwatcherへ接続し、NETWORK_READ許可が必要。非network Toolでは拒否する。Project／Session・deadline・DAG・terminal非上書き等は既存のまま。新kindを読むには対応版が必要。HTTP_BODY_SHA256／HTTP_STATUSは従来動作を維持する。

任意code、正規表現、部分一致／大小比較、複数条件のOR、認証header、外部サービスの品質評価は今回の範囲外。実HTTP検証はloopback fixtureのみ。
