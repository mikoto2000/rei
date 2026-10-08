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

### CI 環境の整合

旧コミットの CI は最新版への置換で停止したが、取得したログに既存回帰失敗があるため保存・報告する。
Windows runner の `C:\Users\RUNNER~1` 形式の一時パスと `toRealPath()` が返す
`C:\Users\runneradmin` の差が、Project / Task の所有確認を含む既存テストの不一致を起こしていた。
CI は runner の正規の一時ディレクトリを TMP / TEMP / java.io.tmpdir に指定する。
LF 前提の YAML fixture は Git の自動 CRLF 変換を無効にしてチェックアウトする。
端末通知の unit test は実 system terminal の代わりに明示的な入出力ストリームを持つ
非 system terminal を使う。headless runner で各ケースに約150秒の待機が発生していたため。
既存テストの選択・アサーションは維持し、全体 CI のテスト除外は追加しない。

最新 CI ではパス・改行の不一致が解消し、3817件中 error 10 / failure 0 / skipped 1。
残りは欧文 native.encoding で日本語を argument file に書けない1件と、
JLine の native terminal provider 探索による入力テストのタイムアウト9件だった。
windows-1252 を明示する Red テストで文字コードエラーを再現し、classpath は argument file に残したまま、
表現できない引数を Unicode のプロセス引数として渡す修正で Green を確認した。
端末 fixture は provider 探索を行わない DumbTerminal を直接生成し、実 JLine reader と既存 assertions は維持する。
関連22件成功（入力13件、通知7件、長いclasspathと文字コード2件）。全体 CI の除外は追加しない。

99874508 の最終ローカル全体回帰は3818件、failure 0 / error 0 / skipped 1、終了コード0（6分41秒）。
GitHub run 37809653438 は設定済みの30分上限を過ぎても in_progress のままでログ取得不可。
通常取消・強制取消を受理した後も状態が変わらず、同じ run の再実行は「既に実行中」と拒否された。
終了原因は未確定で成功扱いしない。この検証履歴の追記で新しい runner の全体回帰を起動する。
ソース、テスト選択、アサーション、CI 上限は変更せず、最新コミットの CI 成功がマージ条件。

別 runner の run 37814110817 も30分上限を超えて終了状態が確定せず、強制取消を要求した。
停止箇所を診断できるよう、CI の同じ Maven 全体回帰を所有子プロセスとして実行する。
子プロセスの締切は20分、期限超過は124、Maven の通常失敗はその非ゼロ終了コードを返す。
15秒ごとの進行表示と従来と同じ Maven 標準ログを残し、テスト・アサーションは除外しない。
1秒締切の試験で124、子プロセス限定の JAVA_HOME 不備で1の伝播を実測した。
追加のレポート・ダンプ送信は行わず、通常の CI 標準ログを診断に使う。

締切付き CI run 37818179082 は全3818件を完了（10分21秒）、failure 2 / error 0 / skipped 1。
端末入力13件は成功し、残り2件は欧文 Windows の argv で日本語が ??? に変わる fixture 引数の破損だった。
OS のコードページで表現できない絵文字を追加し、ローカルでも文字が ?? に変わる Red を確認した。
fixture 補助ランチャーは classpath を argument file に保持し、引数を ASCII Base64 として渡して Java 内で UTF-8 復元する。
固定引数の個数を明示し、コマンドに後から追加される native 引数の既存契約も維持する。
Unicode・長いclasspath・実 native 引数・端末・外部エージェントの関連33件成功。全体回帰を再実行する。
変更前 wrapper の通常成功も全3818件、failure 0 / error 0 / skipped 1、Maven 6分47秒・wrapper終了0を確認済み。

### フェーズ0完了

PR [#42](https://github.com/mikoto2000/rei/pull/42) はマージ済み。
最終コミット `7394b557` のローカル全体回帰は3819件、failure 0 / error 0 / skipped 1、wrapper終了0（6分49秒）。
同じコミットの GitHub CI run `37820606493` も3819件、failure 0 / error 0 / skipped 1で成功（9分37秒）。
保護ルール・必須レビューなし、CI成功を確認して `77c0c812` で main へマージした。
別途起動した rei と最初のプロセス cleanup エラーの因果関係は未確定。再試行後は再発していない。

### フェーズ1: 共通の安全な HTTP 取得

`http.SafeHttpFetcher` を検索プロバイダー、URL本文、WebPageFetcher、Paper に共通化した。
検索本文と URL 本文の既存文字コード判定は維持する。Paper の再試行・Content-Type 検証・公開 API も維持する。
wire bytes は各 ByteBuf をコピーする前、decoded bytes は圧縮展開中の各書き込み前に制限する。
Content-Length がない転送でも実測で制限し、大きい Content-Length は早期拒否する。
identity / gzip（連結メンバーを含む）/ zlib deflate に対応し、未知の Content-Encoding は拒否する。
接続、無通信の読み取り、処理全体の期限を別に扱う。全体期限には DNS、転送、リダイレクト、展開を含み、
Paper のリトライと backoff でもリセットしない。待機中は実行中止を短い間隔で確認し、future・DNS問い合わせ・接続を解放する。

| 設定接頭辞 | wire 上限 | decoded 上限 | 接続 / 読み取り / 全体 | redirect 上限 |
| --- | ---: | ---: | --- | ---: |
| rei.web-search | 2 MiB | 4 MiB | 5s / 10s / 10s | 5 |
| rei.url-fetch | 2 MiB | 4 MiB | 5s / 10s / 30s | 5 |
| rei.paper（API） | 4,000,000 B | 8,000,000 B | 5s / 10s / 30s | 5 |
| rei.paper（PDF） | 20,000,000 B | 20,000,000 B | 5s / 10s / 30s | 5 |

Web/URL のキーは `max-wire-bytes`, `max-decoded-bytes`, `connect-timeout-seconds`,
`read-timeout-seconds`, `timeout-seconds`, `max-redirects`。対応する環境変数は application.yaml に記載。
Paper は `max-response-bytes`, `max-decoded-response-bytes`, `max-pdf-bytes`, `max-decoded-pdf-bytes`,
`connect-timeout`, `read-timeout`, `timeout`, `max-redirects` を使う。
サイズは最大100 MiB、各期限は最大5分、redirectは最大10の設定検証を行う。
これらは有限の暫定初期値であり、実Webのサイズ分布やP95から最適化した値ではない。
全体期限は既存の Web 10秒 / URL 30秒 / Paper 30秒を維持し、Paper の wire 上限も既存値を維持した。
従来の HTML 取得は無制限で実サイズ分布の記録がないため、2 MiB / 4 MiBを暫定の安全上限として設定可能にした。

公開ページは http:80 / https:443 のみ、userinfo・IPv6 zone・localhost・非公開/予約/文書用 IP を拒否する。
DNS の全応答を検証し、検証済み InetSocketAddress を transport へ渡す。
接続後の実 peer も、その問い合わせで検証済みの IP と一致することを確認する。
リダイレクト先でも再検証し、認証等のヘッダーは異なる origin に送らない。TLS のホスト名は元の URI を維持する。
IP分類は [IANA IPv4 Special Registry](https://www.iana.org/assignments/iana-ipv4-special-registry/) と
[IANA IPv6 Special Registry](https://www.iana.org/assignments/iana-ipv6-special-registry/) を参照した保守的な公開アドレス判定。
一部の特殊用途の globally reachable アドレスも拒否する。

管理者が設定する検索プロバイダーは origin を固定し、別 origin への redirect を拒否する。
既存のローカルプロバイダーとの互換性のため、設定 origin が明示的 localhost/loopback のときだけ同じ origin の loopback 接続を認める。
private LAN API が必要な場合だけ provider の `allow-private-network: true` を明示する。
この例外は管理者設定の provider origin に限定され、検索結果のページ URL や fetchUrlContent には適用しない。

実リクエストと受信チャンクを observer で計測し、redirect 後の通信も計数する。拒否された URL はリクエスト数に含めない。
エラーは低カーディナリティのコードで返し、クエリ・本文・認証値をログやメトリクスへ追加しない。
`ToolContext` の実行中止はツールスキーマに公開せず伝播し、通常の取得失敗への fallback で握りつぶさない。
実ソケットの fixture と純粋な境界テストを使用する。ライブWebの速度・転送削減率は未測定。

### フェーズ1完了

PR [#43](https://github.com/mikoto2000/rei/pull/43) を `f3914e18` で main へマージした。
`031a3ec2` のローカル全体回帰3893件、failure 0 / error 0 / skipped 1、wrapper終了0（8分05秒）。
同じ head の CI run `37829967450` も3893件、failure 0 / error 0 / skipped 1で成功（5分38秒）。
merge state CLEAN、未解決レビューコメントなし、必須保護ルールなしを確認してマージした。

### フェーズ2: 段階的検索と取得前の選定

`WebSearchSelection` を WebSearchAndRead と searchKnowledge の WebSearchOrchestrator に共通利用する。
元の検索から始め、件数だけでなく関連語、公式ドメインと質問の製品名の対応、指定バージョン、日付、
独立ドメイン数、正規化後の重複率で追加検索を判断する。メタデータの十分性は候補選定の目安であり回答の正しさの保証ではない。
公式判定を任意の `docs.*` 接頭辞や `.gov` の部分一致から変更した。未知の製品は公式と断定しない。
最新版の判定は出典が主張する日付を使う暫定30日ルールで、日付なし・解析不能・遠い未来は十分と扱わない。
同一ホスト配下のサイトを独立した出典として数えない。ドメインの末尾2ラベルで保守的にまとめるため、
co.jp/co.uk等の複数組織も同一グループになる場合がある。この判定で独立性を過剰に主張しない。

`max-search-queries` は既定3・最大3、`max-search-api-calls` は既定4・最大12。
後者は全クエリ/プロバイダー共通で実通信の開始時に消費し、provider redirect も含める。
`max-page-fetches` は既定5・最大20で、最終候補数・readTopと合わせて **本文取得前** に適用する。
`max-results` は最大20。各環境変数は application.yaml に記載した。
関連性・出典の優先度を評価し、元の検索順位を同点時に維持しながらドメインの多様性を優先する。
readTop=0は元クエリだけを使い、本文は取得しない。LLM/embedding呼び出しは追加しない。

URLは scheme/host の小文字化、既定port・fragmentの除去、dot path の正規化、
utm_*/gclid/fbclid/msclkidのみの除去を行う。ref/source/version/qやクエリ値のエンコード・順序は保持する。
重複候補の元URL/タイトル/日付は alias に保存し、検索結果の本文はそのURLを使って取得する。
本文は **切り詰める前の抽出全文** とコードの原文（字下げを含む）から SHA-256 を計算する。
同一本文は一件にまとめ、引用 alias を統合する。旧コンストラクタや失敗snippetは全文ハッシュを持たず、
短い出力だけで誤って重複扱いしない。HTTP redirect は実際の最終URLを `http_redirect` として保存する。
same-origin canonical は `page_canonical_claim` として保存し、外部ページの主張だけで本文を同一視しない。
これらは出典データであり、ツール権限やシステム指示へ昇格させない。

フェーズ0の同じ固定fixture（最終一件、内容 evidence）の実測は検索3/取得3。
改善後は検索1/取得1で、返すURLと evidence を保持するテストを追加した。
これは mock を使う決定的な回数比較であり、実Webの66.7%高速化・通信削減・Recall改善を意味しない。
品質 fixture の期待出典と evidence を維持し、重複本文は複数行を返す代わりに全引用URLを alias に保持する。

#### CI 停止の追加調査

`4fce9297` のローカル全体回帰は3913件、failure 0 / error 0 / skipped 1、wrapper終了0（7分30秒）。
CI run `37833781348` は20分のプロセス期限を越えても実行中になり、ジョブログ取得は BlobNotFound だった。
通常キャンセルに反応しなかったため force-cancel を要求した。この run を成功とは扱わない。

外部エージェントの子プロセス終了処理には、終了済み root の descendants を再列挙し、
取得したハンドルを無条件に終了する問題があった。モックのプロセスだけを用いる境界テストで4件の失敗を再現した。
root が生存し開始時刻を確認できる間だけ列挙し、列挙の前後で同じ開始時刻と生存を確認する。
root より古い、開始時刻不明、現在の JVM またはその祖先のハンドルを拒否する。
確認済みのハンドルは root 終了後も保持し、既存の子プロセスキャンセルを維持する。
確認できないプロセスを終了しないため、瞬時に孤児化した子の追跡には限界がある。

OpenJDK の [ProcessHandleImpl](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/lang/ProcessHandleImpl.java)
は parent PID と開始時刻から descendants を探索する。root 終了後の古い parent PID により、
無関係なプロセスが候補に入る可能性を考慮した修正である。
現在の CI 停止や別途起動した rei との因果関係はログが取得できず未確定。
テストを除外せず、所有を検証したプロセスだけを終了するようにした。
