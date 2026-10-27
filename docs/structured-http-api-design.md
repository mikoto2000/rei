# 構造化 HTTP API: Phase 0 調査・設計

基準: 2026-10-28 に fetch した origin/main `b9ff92331a1551e76e87be75bafcf602c902cf94`。
既存 checkout の `_handoff_src/`, `emudhra.html` は変更しない。

## 現状と根拠（基準コミットの行番号）

| 領域 | 根拠 | 判断 |
| --- | --- | --- |
| Shell | `src/main/java/dev/mikoto2000/rei/core/Tools.java:978` | runCommand は foreground/background/auto、プロセス管理を維持 |
| Web 取得 | `src/main/java/dev/mikoto2000/rei/urlfetch/UrlContentFetchTools.java:31`, `websearch/WebSearchTools.java:83` | HTML と検索は既存ツールを使用 |
| HTTP | `http/SafeHttpFetcher.java:55`, `http/SafeHttpFetcher.java:136` | 低レベル exchange にメソッドと body の overload を追加。HTML fetch の契約は維持 |
| SSRF | `http/PublicNetworkPolicy.java:22`, `http/HttpFetchPolicy.java:82`, `http/ValidatingResolverGroup.java` | DNS 回答検証、検証済み実 peer 固定、Origin 比較を再利用 |
| 制限 | `http/SafeHttpFetcher.java:160`, `http/BoundedBody.java`, `http/FetchOperation.java:77` | 接続・read・総期限、圧縮前後上限、future cancel を再利用 |
| キャンセル | `http/FetchScope.java:118` | ToolContext の RunExecutionContext と停止例外を維持 |
| 権限 | `core/policy/ActionCapability.java:12`, `core/policy/ToolPermissionPolicy.java:296`, `core/policy/ToolPermissionGuard.java:122` | 既存 NETWORK_READ/NETWORK_WRITE を使用。WRITE の READ への再分類は禁止 |
| 承認 | `core/policy/ToolPermissionGuard.java:174`, `core/policy/ToolApprovalRepository.java` | 入力全体に対する既存一回・session/project 承認を使用 |
| 並列・委譲 | `core/policy/ToolPermissionPolicy.java:469`, `subagent/SubAgentToolPolicy.java:13`, `subagent/SubAgentToolCatalog.java:78` | READ だけを子に公開、WRITE は allowlist 外、intrinsic 分類で並列を防止 |
| 登録 | `core/configuration/AiConfiguration.java:385`, `event/ToolEventCallbackDecorator.java:99` | Spring AI @Tool を既存登録・イベント境界に追加 |
| Secret | `github/SecretaryGitHubProvider.java:39`, `chatgpt/ProtectedCredentialStore.java:26` | 汎用 Secret store はない。既存 GitHub と同じ環境変数参照を採用。ChatGPT 専用暗号化 store を転用しない、平文永続化なし |
| 計測 | `websearch/WebSearchMetrics.java:21` | 同じ Micrometer 基盤で固定ラベルの集計。本文や URL の保存なし |

上記パスは `src/main/java/dev/mikoto2000/rei/` 起点（明記したものを除く）。

## 設計判断

- `httpRequestRead` (GET/HEAD) と `httpRequestWrite` (POST/PUT/PATCH/DELETE) を別公開。
- JSON Pointer は既存 Jackson で処理、パース前に byte 上限を強制。配列は maxItems で切り詰め、非配列はそのまま。
- 通信失敗と HTTP 4xx/5xx、JSON 構文・パス不一致を区別。停止制御例外は握り潰さない。
- REST サービスは HTML 抽出・HTTP cache を経由しない。WRITE の自動 retry は無効化し、redirect も拒否して再送を避ける。
- API のリダイレクトは同じ Origin の READ のみ。全 hop を再検証、認証 profile の Origin を維持する。
- ホスト設定 `rei.http-api` の厳密 Origin allowlist だけ内部通信を許可。設定変更ツールを設けない。
- 認証 profile は Origin、方式、環境変数名だけを設定し、Secret は実行直前にホストで解決。任意認証ヘッダーは禁止。応答への Secret 反射を伏せる。
- 副作用を持つ GET の既知 resource はホスト設定のパス prefix で READ 拒否し、WRITE ツールでも GET を許可しない。用途別ツールによる別途の安全な公開が必要。
- Shell は任意通信を可能にするため、既存の保守的 capability 分類を弱めない。明白な HTTP コマンドは追加のネットワーク権限を強制し、汎用 shell sandbox で完全遮断できない限界を記録する。
- 不要な依存は追加しない。既存 fetch は安全上、DNS/peer 検証のため接続を再利用していない。その判断を維持する。

## 検証方針

各機能は Red → Green → Refactor を記録し、小さく commit する。HTTP は loopback テストサーバー、DNS は既存 resolver/peer テスト、TLS はローカル証明書 fixture で検証。phase ごとに targeted tests、完了前に full profile と既存静的解析・architecture gate を実行する。
