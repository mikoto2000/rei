# HTTP本文SHA-256の依存待機

既存registerDependencyへHTTP_BODY_SHA256を追加した。targetはHTTP/HTTPS URL、expectedはstatusと本文SHA-256を`200:64桁hex`形式で指定する。

```json
{
  "kind": "HTTP_BODY_SHA256",
  "target": "https://example.invalid/ready",
  "expected": "200:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "lifetime": "PT1H"
}
```

例のdigestは空本文。SHAは大文字入力を小文字へ正規化する。HTTP statusは100..599、URLのuser info／fragmentは既存条件と同じく拒否する。登録でネットワーク観測は実行しない。

checkHttpDependency／waitForHttpDependencyと、明示有効化した既存watcherから利用する。非networkのcheckDependency／waitForDependencyでは拒否し、Shell checkもNETWORK_READの既存guardへ接続する。自動監視はwatcher有効・Policy強制・NETWORK_READ自動許可が揃った場合だけ観測する。Project／Session・deadline・DAG・CAS・terminal非上書き・Scheduler接続は既存のまま。

1回のGETでstatusと受信完了した本文全体のdigestが一致するとCOMPLETED／http_body_digest_verified。不一致はWAITING／http_status_mismatchまたはhttp_body_digest_mismatch。timeoutや途中切断などはWAITING／http_unavailable。本文64KiB超過はBLOCKED／http_body_too_large。旧custom HTTP portは本文未対応ならBLOCKED／http_body_verification_unsupportedを返し、statusだけで完了にしない。

各request最大2秒、redirectなし、認証headerなし。waitは既存の最大60秒・最小1秒間隔。ByteBufferを逐次hashし、本文を保持・decode・SQLite保存・返却しない。サイズ条件は受け入れる本文の上限で、開始済みの通信量の厳密な上限ではない。取消では所有requestをcancelし、既存のinterruptを保持する。

ハッシュ対象はHttpClientが届けた本文byte列。charset変換・JSON正規化・gzip展開は追加しない。動的な日時や空白も異なるdigestになる。今回の条件は既知の完全本文の一致であり、任意code predicate／部分文字列／自動baseline取得・書き込みは対象外。JSON field条件は後続の[HTTP JSON値依存条件](http-json-dependency.md)で対応した。

既存SQLiteのkind/expectedへ保存するため、新しいDB/table/migrationは追加しない。新kindを読むには対応版が必要で、旧アプリへのdowngrade互換は保証しない。外部の実サービスは呼び出さず、検証はloopback HTTP fixtureで行った。
