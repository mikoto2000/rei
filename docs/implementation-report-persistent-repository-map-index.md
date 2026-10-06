# Repository Map 永続索引 実装記録

C18の再起動cacheへ、既定無効のSQLite Java AST metadata snapshotを追加した。canonical root SHA・parser/JDK profile・現在inventory・現在ソースSHAを毎回照合し、本文を保存せずpackage/symbol/import/entry metadataだけを再利用する。1 DB/1 active root、1024件/各64KiB/合計8MiBとstrict JSON/checksumの境界を維持する。

未実装store/constructor APIのcompile Red→2テストGreen→8新規テスト・Repository Map/Change Impact/Git Impact/config関連PASS。実compiler呼出の非実施・変更時再解析、DB破損/障害、root/profile/deletion、過大入力・SQL rollback、取消、既定SQL非実行、partial走査で旧完全snapshotを残すことを検証した。

全体回帰3283 tests / 610 suites、failure/error/skip各0。feature 3ecdb86cをCommit/Pushしmain d2216600へMerge済み。Merge後関連テストPASS、main Push済み。Java/configのみでNative/React変更なし。

[設定・鮮度・上限・snapshot境界](persistent-repository-map-index.md)。多言語・意味的依存解決はこの索引では実装したとは扱わない。
