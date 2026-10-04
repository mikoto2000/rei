# Change/Test Impact

`changeTestImpact(changedFiles, limit)` は明示されたProject相対変更pathからJavaの影響候補を返すREAD Tool。
1〜64path、limitは1〜100（既定20）。CHATと明示要求したSubAgentから利用する。
Git差分の自動収集、command生成、test実行・省略の決定は行わない。

Repository Mapの同じ走査・内容ハッシュcacheを再利用する。内部snapshotは表示の100file/200relation
上限を適用せず、索引自体の1024file/16MiB/10秒等の制限を保持する。
別のparser・ファイルcache・推測したRelatedFileGraph関係は追加しない。

明示importの逆方向とTEST_NAME_CANDIDATEを幅優先で推移的にたどり、cycleと重複を除く。
各候補はpath、test配下か、最短distance、最後のedge種別とviaを持つ。
変更ファイル自身はCHANGED、テスト候補を優先してdistance/path順で返す。
これはcoverage保証ではない。同一packageの暗黙参照、wildcard、reflection、resource、build設定、
他言語による影響は未対応。warningsに必ずその限界を示す。

削除・除外・走査上限などで索引に存在しない変更はunindexedChangesに入り、partial=trueとなる。
解析不完全・候補上限でもpartial=true。partial=falseも完全な影響範囲を保証しない。
version/scannedAt/rootで根拠の索引を確認し、広い回帰テストも実施する。

TDD: 未実装compile失敗後、逆import/テスト候補、削除/更新、root escape、入力上限、
表示100fileを超える索引とcycle/候補上限を検証。完全な意味解析・coverage対応はDeferred。

全体回帰: full profile 2809 tests / 540 suites、failure/error/skip 0。
