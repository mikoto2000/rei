# HTTP JSON値依存条件 実装記録

## 変更

監査A4のJSON field条件を既存永続依存へ追加した。HTTP_JSON_VALUEは固定JSON Pointerのscalarを検証し、statusだけや自由文の成功主張で完了にしない。既存network分類・SourceProbe・Tool説明へ接続し、旧custom HTTP portは未対応をBLOCKEDとする。新DB／Provider／LLMは追加しない。

本文をrequest内で最大64KiBだけ保持し、strict JSON解析・深さ／数値／string上限と型付き比較を行う。応答本文／実値／parserエラーは保存・返却しない。既存の2秒・redirectなし・取消とNETWORK_READ／Project／Session境界を使う。

## 検証

初期RedはHTTP_JSON_VALUE／probeJson未実装によるコンパイル失敗。初期Greenで実HTTPのboolean一致／不一致・stringとの型区別と旧port未対応拒否を確認した。

追加の振る舞いRedで近接小数0.1と0.10000000000000001の誤一致を1 failure / 0 errorで確認した。浮動小数をBigDecimalで解析して数学的に比較するよう修正した。

追加テストで小数精度／巨大指数・nullと欠落・pointer escape、重複キー／trailing data／不正JSON／深さ上限、64KiBちょうど／超過、status不一致・redirect非追跡・途中切断、SQLite再生成・非network拒否・標準Policyで通信なし・許可済みwatcherの実GET・terminal waitの再GETなし・監査の本文非保存、開始前／受信中の取消を確認した。

最終関連テスト（HttpJsonDependencyTest・HttpBodyDependencyTest・HttpDependencyProbeTest・DependencyToolsTest・DependencyObservationServiceTest・DependencySourceProbeTest・PersistentDependencyRepositoryTest・DependencyHttpTest）はPASS。全体回帰は3116 tests / 594 suites、failure/error/skip各0。Javaのみ変更し、Native/Reactは再実行していない。feature 933bcc87をPushし、main 706102f5へMergeした。Merge後関連テストもPASS、main Push済み。詳しい範囲は[http-json-dependency.md](http-json-dependency.md)。
