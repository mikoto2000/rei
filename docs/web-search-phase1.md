# Web 検索改善 Phase 1: 障害耐性と PSL

## 調査と変更範囲

開始時の `origin/main` は `a55cc5a5`。追加検索が IOException または通常の RuntimeException で失敗すると、
WebResearchPipeline のループが break し、candidates に残る未取得ページも処理されなかった。
検索候補は candidates、取得結果は loaded、取得済み URL は attempted に別々に保持されている。
失敗時もこの状態を維持したまま pending を通常の batch.fetch に渡す。
初回検索の全面障害はサービス層では例外、部分的な追加検索障害は omissions の
additional_search_failed と assessment=unknown、本文障害はページの fetchStatus/errorType で区別する。

検索順序、正常時の充足停止、URL 正規化、取得・検索予算、期限、並列取得、キャッシュ、関連抜粋は維持する。
追加検索障害後も次の未実行検索語を試せるが、登録済み検索語・最大検索回数・最大取得数で有限に停止する。
同じ URL の再取得は attempted で防ぐ。キャンセル・中断は FetchOperation.propagateControls で伝播する。
ログは処理種別、例外型、安全なエラーコードのみを記録し、例外メッセージ・スタック・URL・本文は記録しない。

## 検索障害時のエージェント継続

`webSearch` / `webSearchAndRead` の実行境界では、検索先の HTTP エラー・通信障害・取得制限を
`success=false` のツール結果としてモデルへ返す。`tool.failed` は保持し、成功イベントには置き換えない。
同じ応答内に続くローカル操作も実行できる。結果には、同じ検索の再試行を繰り返さず、
既知の公式 URL の取得や外部確認が不要なレビュー・修正を続け、未確認事項は記録する案内を含める。
キャンセル・中断・実行停止・プログラム内部の不整合は、この回復処理の対象外とする。

検索先の HTTP ステータスは `WebSearchHttpException` に保持し、モデルへの結果の `httpStatus` と
失敗イベントのメッセージに残す。402 は `payment_required` として契約・請求設定・利用枠の確認を案内する。
応答本文・URL・認証情報・例外ラッパーのメッセージは結果に含めない。

各 provider の例外は `WebSearchService` の WARN ログに `provider`、`causeType`、
`code`、`httpStatus` を記録する。後続 provider が成功した場合にも、先行 provider の障害を残す。
HTTP エラーは `HTTP_ERROR` とステータス、通信・取得制限は `HttpFetchException.Code`、
その他は `PROVIDER_ERROR` と例外型で区別する。中断・キャンセルは provider 障害として記録しない。
例外メッセージ・スタック・検索語・URL・応答本文・認証情報はログに含めず、未対応の provider 名は
`unsupported` に置き換える。
正常な HTTP 応答から候補を抽出できなかった場合は、INFO に `code=NO_RESULTS` と
provider・HTTP ステータスを記録する。これは障害と断定せず、検索結果0件や HTML 形式の違い、
確認画面などの可能性を調べる手掛かりにする。検索順序・フォールバックの動作は変更しない。

`WebSearchServiceTest` で Brave / DuckDuckGo の HTTP ステータス保持、
`FailureDiagnosticsTest` と `ToolEventCallbackDecoratorTest` で分類・失敗通知・中断の伝播、
`ToolLoopSupportTest` で実際の検索ツールコールが 402 になっても後続のローカル修正が実行されることを検証する。

## PSL による出典識別

既存の google-api-client 経由で Guava 33.1.0-jre が解決済みだったため、同じバージョンを直接依存として明示した。
InternetDomainName.isUnderPublicSuffix / topPrivateDomain を使用し、PSL の ICANN / private セクションを含める。
ライブラリ同梱データを利用し、検索時の PSL ダウンロードや DNS 問い合わせは行わない。
参考: [Guava InternetDomainName API](https://guava.dev/releases/33.4.0-jre/api/docs/com/google/common/net/InternetDomainName.html)

- a.co.jp と b.co.jp は別出典、www.a.co.jp と docs.a.co.jp は同一出典。
- github.io などの private suffix 配下もテナント別に識別する。wildcard / exception 規則もライブラリに委ねる。
- IDN は IDN.toASCII の STD3 規則で ASCII に統一し、大文字・末尾ドット・ポートの差を取り除く。
- IP は Guava InetAddresses で DNS を使わず正規化する。
- localhost、public suffix 自体、未知 suffix の有効なホストはホスト全体を識別子とする。
- 不正 URL・ホスト、ホストなし、HTTP(S) 以外、userinfo 付き URL は null とし、独立出典数から除外する。
  不正値を空文字などの共通識別子へまとめない。結果選択では有効な出典を優先し、残り枠の既存挙動は維持する。

利用箇所は候補のドメイン分散、検索候補の複数出典判定、本文評価の independentSources。
公開 API・結果形式は変更せず、評価アルゴリズムのうち出典識別と無効値除外のみを修正する。
この識別子はネットワーク接続の許可判定ではなく、既存 SafeHttpFetcher の安全性検証はそのまま使う。
PSL はライブラリ更新時に更新されるため、将来追加された suffix が直ちに反映されるわけではない。

## TDD と検証

再現テストを実装前に追加した。最初の実行はテストコードの assertThrows 型推論でコンパイル失敗し、
テスト側を修正してから実装に起因する Red を確認した。
検索要求予算 REQUEST_BUDGET は既存の「新規検索だけ終了、既存ページ取得は継続」という契約に合わせて検証する。
修正前の最終 Red: 55 tests / 34 failures / 0 errors / 0 skipped。
追加検索障害後の取得件数 expected 5 / actual 1、.co.jp の独立出典数 expected 2 / actual 1 などを再現した。

WebResearchResilienceTest は正常停止、追加候補優先、連続検索障害、取得済み本文保持、一部本文障害、
上限、候補なし、初回全面障害、直接・ラップされた中断とキャンセル、安全な診断ログを扱う。
WebRegistrableDomainTest は一般ドメイン、.co.jp/.co.uk、IDN、IP、localhost、未知 suffix、private suffix、
wildcard/exception、不正 URL と、選択・検索充足度・本文の複数出典判定を扱う。

検証ログは作業用 target/phase1-* に保存する。最終結果は PR 本文にも記載する。

## Phase 2 以降

検索意図分析、日本語の意味的類似度、関連度スコア、本文の意味的充足度、不足理由に応じた検索語生成、
公式性・鮮度・矛盾検出は変更しない。実 Web の Recall・P50/P95 の改善率は今回測定していない。

### 追加の回帰と最小変更

部分検索結果を保持した後の検索障害も診断から落ちないよう、WebResearchScope に障害の有無だけを保持する。
エラー分類・安全なログは WebSearchSelection に置き、既存のクラス間依存を増やさない。
部分候補の後の通常障害2件と TOTAL_TIMEOUT / TRANSFER_BUDGET 2件は、追加 Red で4件とも失敗を確認した。
候補がある場合に IOException にラップされた中断を握りつぶすケースも、追加 Red 1件の失敗後に修正した。
REQUEST_BUDGET は既存契約を維持し、検索だけを止めて未取得ページの取得を許可する。

初回のスコープ側エラー分類は構造チェックで新しい循環依存内の接続2本を検出したため採用せず、
分類を既存の WebSearchSelection へ移した。依存許容リスト、テスト除外、静的解析基準は変更していない。
追加テストは障害耐性21件・ドメイン39件の計60件。関連回帰は267件が成功した。
途中段階の全体回帰は6277件、failure 0 / error 0 / skipped 5、終了0（15分58秒）。
これは最終診断共有の修正前の記録であり、最終差分の全体回帰は下記に別記する。

### 既存の整形違反

全体の spotless:check は以下の既存3ファイルで失敗した。開始時 origin/main a55cc5a5 と作業ファイルの
Git blob hash が一致することを確認したため、今回の変更とは区別して残す。未変更ファイルの一括整形は行わない。

| ファイル（src/test/java/dev/mikoto2000/rei/ 配下） | main と一致する blob hash |
| --- | --- |
| core/chat/ConversationLifecycleAdvisorTest.java | e1198031fa83853cd6188dddcdd2cf22935852ec |
| externalagent/ExternalAgentReviewAdvisorTest.java | 4894176880edef2cfa7cc6df384b484f3a175702 |
| subagent/DurableSubAgentRepositoryTest.java | 4ad09036edcb27a8c16659fd306b953cc1138f99 |

変更対象の Java 6ファイルは Spotless で整形・検証する。全体 Spotless は現行 GitHub Actions の必須ゲートではない。
Checkstyle と SpotBugs は現行 CI と同じレポート生成・新規指摘ゲートで検証し、既存の SpotBugs 指摘を隠さない。

### 最終コードの個別チェック

- 新規テスト60件と構造チェック7件を同時実行: 67件、failure 0 / error 0 / skipped 0。
- Checkstyle: 指摘0件。SpotBugs: 既存1897件、新規0件。現行 CI の増分ゲート成功。
- 変更 Java 6ファイルの Spotless: 成功。全体 Spotless の既存3ファイルの違反は上記のとおり。
- 静的解析ゲートの自己テスト8ケース: 成功。
- IDN の非 ASCII トップレベルドメイン（punycode）も、同梱 PSL で判定されることをオフラインで確認した。

全体回帰のスキップ5件は Windows/WSL の外部プロセス用環境変数がない3件、PlantUML 1件、
任意 I/O ベンチマーク用プロパティがない1件。新規60件のスキップはない。

### 最終全体回帰

最終ソースで CI と同じ .github/ci/full-test.ps1 を実行し、6281件、failure 0 / error 0 / skipped 5、
終了コード0（14分18秒）を確認した。成功6276件、既存の環境依存スキップ5件である。
全テストの選択・アサーション・CI タイムアウト・静的解析基準は変更していない。
GitHub Actions の結果とマージ情報は PR に記載する。
