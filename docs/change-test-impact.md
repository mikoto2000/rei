# Change/Test Impact

`changeTestImpact(changedFiles, limit)` はProject相対変更pathからJavaの影響候補を返すREAD Tool。
1〜64path、limitは1〜100（既定20）。CHATと明示要求したSubAgentから利用する。
changedFiles省略時はGitのステージ済み・未ステージ・未追跡変更を自動収集する。明示空配列は従来どおり拒否する。
command生成、test実行・省略の決定は行わない。

省略時は捕捉したProjectがGitの最上位rootであり、HEAD commitがあることを要求する。
ステージ済みと未ステージを別々に取得し、作業ツリーがHEADと一致してもステージの変更を見落とさない。
rename検出を無効にして旧・新pathを含め、削除pathは既存のunindexedChangesへ渡す。
NUL区切りで空白・日本語pathを保持し、Gitignore対象の未追跡fileは含めない。
秘密情報・生成物用の既存除外pathは結果から除き、partial/warningsと広い回帰の必要性を示す。
除外前64path超過、各command出力1MiB超過、失敗・timeout・不完全出力は拒否し、取消を伝播する。
各command最大5秒、収集の共有deadline15秒。index書込み、diff本文取得、外部diff/textconvは実行しない。
複数のGit観測と索引は原子的patch snapshotではなく、同時変更は起こり得る。結果のwarningsを確認する。
詳細な検証は[Git自動収集の実装記録](implementation-report-git-change-test-impact.md)を参照。

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
