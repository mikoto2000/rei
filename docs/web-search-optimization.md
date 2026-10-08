# Web 検索の効率・品質改善

## 調査結果と Phase 0

`webSearch` → `WebSearchService` は設定順に DuckDuckGo / Brave の全プロバイダーを呼ぶ。
`webSearchAndRead` → `WebSearchAndReadService` は上位3件を `UrlContentFetchService` で直列取得する。
`searchKnowledge` → `SearchKnowledgeService` → `WebSearchOrchestrator` は
`WebSearchQueryPlanner` の元クエリ / official / latest を全実行し、URL文字列で重複排除、
全候補を `WebPageFetcher` で直列取得後、`WebSearchAggregator` で最終件数を制限する。
`WebPageExtractor` は Jsoup で本文先頭2000文字を抽出する。

Paper Research の `SafePaperHttpClient` は Reactor Netty の resolver で実際の接続先を検証し、
リダイレクトも再検証する。PDF用サイズ制限、`PaperOperation` のキャンセル・Future停止がある。
通常ページ取得は別の JDK HTTP 実装でサイズ制限がなく、URL要約の文字コード判定のみ共通化済み。
Web専用キャッシュ・並列本文取得・関連箇所スコアリングは未実装。
既存の lexical / rerank は VectorStore にあり、検索ごとの embedding は追加しない方針。

## 計測

既存 Actuator / Micrometer の global registry を利用する。
`rei.web.search.events` は event ごとの観測件数、`rei.web.search.providers` は
duckduckgo / brave / other の実際の Search API 呼び出し数、`rei.web.search.http` はステータス数。
`rei.web.search.duration` は search / fetch / total の Timer と P50 / P95。
URL・クエリ・本文・APIキーをラベルや計測ログに含めない。

events: search_api_calls, http_requests, results, duplicate_urls, fetch_candidates,
fetch_successes, fetch_failures, timeouts, cancellations, additional_searches,
output_characters, estimated_tokens, received_bytes。
同じURLがプロバイダー統合時・展開クエリ統合時に重複した場合は、それぞれの除外を数える。
文字数は返却本文の量であり、実際のモデル入力・課金トークンとは区別する。
推定トークンは既存の共通 `TokenEstimator.conservative()` を再利用する。
ASCII は4文字あたり1、非ASCIIのコードポイントは1文字あたり2を目安にした予算用近似。

キャッシュ、single-flight、リダイレクトは未実装なので観測値を生成しない。
Phase 0 の received_bytes は受信 body の byte[] 長を観測する。HTTPヘッダー・TLS通信量は含まない。
途中失敗したレスポンスの部分受信量も未計測。HTTPサイズ計測の共通化は Phase 1。
P50/P95 は MeterRegistry の観測窓・exporter に依存し、少数標本では比較に使わない。
現在はプロセス集計であり Run / Session ごとの集計は未実装。
Actuator公開設定は既存の認証・公開範囲に従う。

## 品質回帰

`WebSearchQualityRegressionTest` は外部通信なしの固定検索・本文 fixture。
一般技術、公式、バージョン、最新、複数サイト要求、日本語、英語、少数結果、
障害、本文失敗の10シナリオで出典維持と本文根拠維持を独立に検証する。
公式・鮮度・複数独立出典の充足を評価する本格的な品質判定は Phase 2 / 5 の対象。
このテストだけで実検索 Recall の改善を主張しない。
既存 provider localhost integration tests は固定 HTTP 応答の解析・統合順を検証する。

実行: `./mvnw -Pfull -Dtest='WebSearch*Test,WebPage*Test,SearchKnowledgeServiceTest,UrlContentFetchServiceTest' test`。
比較時は同じ fixture・設定・ウォームアップ・キャッシュ状態・繰返し数を使う。
削減率=(Before−After)/Before×100、成功率・Recall は絶対値と差を比較する。

| 指標 | Phase 0 | 最終 | 改善率 |
|---|---|---|---|
| Search API 回数 | 未計測 | 未計測 | 未計測 |
| HTTP 回数 | 未計測 | 未計測 | 未計測 |
| 本文取得ページ数 | 未計測 | 未計測 | 未計測 |
| 受信バイト数 | 未計測 | 未計測 | 未計測 |
| P50 / P95 | 未計測 | 未計測 | 未計測 |
| モデル入力推定トークン | 未計測 | 未計測 | 未計測 |
| 根拠取得成功率 / Recall | 未計測 | 未計測 | 未計測 |

計測基盤の追加は実環境性能の計測結果を意味しない。

`measuresLegacyBaselineWithoutNetwork` の固定 fixture（3展開クエリ、各1件の独立候補、
最終 limit=1、本文は全て `evidence`、キャッシュなし）では検索サービス呼び出し3回、
追加検索2回、本文取得3回、返却出典1件を直接検証する。
Search API / HTTP はモックなので実通信件数や速度のベースラインではない。

## Phase 0 検証とマージ阻害要因（2026-10-09 JST）

TDD: `WebSearchMetricsTest` はクラス未実装のコンパイル失敗（Red）を確認後に実装。
検索・本文取得の関連テストは成功。最終差分の再検証結果は PR に記載する。
全体 `./mvnw -q -Pfull test` は **3815件、failure 0、error 1、skipped 1** で失敗。
既存の `DocumentRendererProcessTest` の PlantUML テストが skip。
既存テストを削除・無効化していない。

エラーは `ImplementationDelegationIntegrationTest` の CODEX ケース。
`ExternalAgentProcessRunner.cleanup()` の `child.destroyForcibly()` が
`IllegalStateException: destroy of current process not allowed` を送出した。
同クラスは今回の差分に含まれず main と同じ実装。
`ProcessHandle` の収集後に生存確認だけで終了しており、自 JVM を対象外にする保証、
終了対象の起動時刻・親子関係の同一性確認を検証する必要がある。
Windows の PID 再利用等が原因かどうかは未確定で、原因を断定しない。
単に例外を握り潰す修正では対象外プロセスの終了リスクを解決できない。

対策案: 自 JVM・祖先を明示的に除外し、収集時と終了時のプロセス識別を保証する。
Windows の Job Object 等の所有プロセス境界も比較検討し、終了済み・PID再利用・
親終了後の子・キャンセル競合の回帰テストを追加する。
安全性問題を報告して無理にマージしないという依頼の条件に従い、Phase 0 を保留する。
後続 Phase は先行 Phase のマージ・main 同期が前提のため着手しない。
全体テストの失敗を関連テストの成功で置き換えない。

### 再試行と続行

ユーザーの再試行・続行指示に従い、既存 Java プロセスがない状態から
`bb4efacf` の全体回帰を再実行した。今回の XML 集計は **3816件、failure 0、error 0、skipped 1**、
終了コード0。前回失敗した CODEX ケースを含め両外部エージェントケースが成功した。
初回実行後に追加された baseline テスト1件も実行されたため件数が1件増えている。
別起動の Rei が初回失敗の原因かは未確定で、初回失敗の履歴を保持する。
CI 成功とレビュー要件を確認後、Phase 0 をマージして後続 Phase を開始する。

続行前のレビューで、推定トークンを既存の共通予算管理と同じ `TokenEstimator` に統一した。
日本語・英語混在の Red テストで expected 10 / actual 5 の不一致を確認後に修正。
これによって Web 独自の推定式を追加せず、モデル入力の計測・予算と整合する。
