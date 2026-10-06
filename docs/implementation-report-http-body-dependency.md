# HTTP本文検証の依存待機 実装記録

## 実装

監査A4のHTTP body条件として、既存HTTP status待機へbounded SHA-256一致を追加した。DependencySpec.networkで2種のHTTP条件を分類し、SourceProbe・ObservationService・Tool・Shellのネットワーク境界へ接続する。旧HTTP_STATUSとFunctionalInterfaceの旧portは維持し、本文未対応portはBLOCKEDで完了を拒否する。

JavaHttpDependencyProbeは既存client・no redirect・2秒・cancelを再利用し、受信ByteBufferを逐次hashする。本文64KiB超過を止め、本文をdecode／保存／返却しない。SQLite条件・監査・期限・DAG・terminal規則を既存のまま使う。

## 検証

初期RedはHTTP_BODY_SHA256／probeBody／network未実装によるコンパイル失敗で確認した。初期Greenでは実loopback GETのstatus＋SHA一致／不一致と旧port拒否を確認した。

結合テストで永続監査のreasonが英小文字／underscoreのみである規則に新コードが適合しないことを0 failure / 1 errorで検出した。既存の規則を維持し、http_body_digest_verified／mismatchへ修正後に関連テストがPASSした。

追加の実HTTP fixtureでchunked日本語byte・64KiBちょうど／超過・空本文・redirect非追跡・途中切断／timeout・開始前／受信中の取消を確認した。SQLite再生成後の条件保持、非network Tool拒否、標準Policyで自動観測なし、NETWORK_READ許可したwatcherの実観測、terminal waitの追加request非実行、本文の監査非保存も検証した。

関連テスト（HttpBodyDependencyTest・HttpDependencyProbeTest・DependencyToolsTest・DependencyObservationServiceTest・DependencySourceProbeTest・PersistentDependencyRepositoryTest・DependencyHttpTest）はPASS。全体回帰は3110 tests / 593 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactは再実行していない。feature bdc1b3cfをPushし、main 7eece12bへMergeした。Merge後関連テストもPASS、main Push済み。範囲と制限は[http-body-dependency.md](http-body-dependency.md)。
