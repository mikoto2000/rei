# 自発的行動の総合検証と拡張方針

Phase 0～7 で追加した機能を、Phase 8 で実コンポーネントの結合と全体回帰から確認します。
利用手順・設定値・API の一覧は [利用ガイド](autonomous-action.md)、当初設計との差分は
[Phase 0 設計](autonomous-action-design.md) を参照してください。

## 初期版の範囲

自発処理は `PENDING_REVIEW` の保存と通知までです。ユーザーが一覧から選んだ最新の全文・
投稿先・版を確認し、既存 Bluesky 設定と送信権限で公開します。新しい本人確認器、
別 OS アカウント、常駐送信サービスは導入していません。
API の `api-client`、従来 Shell の `shell` は操作元の記録であり、個人の本人確認ではありません。
共通 API キーを持つクライアントや同じ OS 権限で任意コードを実行するプログラムから、
公開操作・設定・DB・資格情報を保護する保証は対象外です。

専用生成経路には通常 Tool、SubAgent、通常会話の advisors、公開サービスを渡しません。
そのため生成モデルに承認を要求したり `--fullauto` を指定したりしても、この経路から公開しません。
既存の通常 Bluesky Tool の権限・fullauto 動作は維持します。この互換性を、同権限の任意プログラムに
対する人間の偽装防止と混同しません。強い保証の追加には別の信頼境界と検証が必要です。

## 結合検証

`bluesky.AutonomousLifecycleIntegrationTest` は本番の SQLite store・資料取得・生成 service・
Attention・outbox dispatcher・公開 service・Bluesky transport・HTTP client を組み合わせます。
モデルと投稿先 DID の解決だけを固定し、全通信先を動的ポートの loopback HTTP サーバーに注入します。
実アカウントを認証せず、実サービスに投稿しません。

| 一連の操作 | 確認する結果 |
| --- | --- |
| 定期契機で生成→outbox 配送→通知 ACK→送信権限拒否 | 未承認ドラフトを保持し、認証・投稿・照合の HTTP 要求は全てゼロ |
| 確認版を公開→サーバーが受理後に応答本文を切断→store 再起動 | `UNKNOWN` を保持。再公開を拒否し、認証・createRecord は各一回だけ |
| 結果不明を明示照合→公開・生成履歴→同じ本文の再生成 | 固定 DID/rkey の GET 一回で凍結 JSON 全体を照合。元の操作元を保持して確定し、二件目を保存・送信しない |
| 編集→古い版の公開→新しい版の公開 | 古い版は通信前に拒否。編集後に確認した本文・改行だけを一回送信 |
| モデル応答に承認フラグ・人間 actor を追加 | 厳密な応答検証で失敗し、ドラフト・通知・公開を作らない |

結合テストは既存動作の characterization です。最初の fixture では JSON の Content-Type が
欠けていたため既存 client が認証応答を拒否しました。fixture を修正すると四件が成功し、
製品コードの修正は不要でした。これを製品の Red→Green 改修とは数えません。
Phase 1～7 の個々の機能追加は、拒否・競合条件の失敗を確認してから実装しています。

## 安全性の回帰観点

| 境界 | 検証箇所・保証範囲 |
| --- | --- |
| 公開情報だけを取得 | `AutonomousPublicSourcesTest`、`AutonomousGenerationServiceTest`。SQL の公開用 projection は許可済み現行本文と ID/版だけを取得 |
| 実際のモデル要求 | `IsolatedAutonomousPostGeneratorTest`。既定・専用・フォールバックの HTTP 入力に会話・Memory・Activity・Tool・資格情報・非公開理由を混ぜない |
| 厳密な応答 | `AutonomousPostResponseTest`、`AutonomousDraftContentTest`。未知項目、偽装承認、重複キー、末尾 JSON、不正 Unicode、本文制限を拒否 |
| 確認版・状態 | store/publication service と HTTP/Shell テスト。revision/hash/DID/期限、二接続競合、連打、編集・再生成の競合を検証 |
| 外部送信 | `BlueskyAutonomousPublicationTransportTest`、`BlueskyRecordHttpTest`。既存権限・設定の前後再確認、認証 DID、固定レコード、本文受信期限・サイズ、曖昧な応答を検証 |
| 不明・異常終了 | publication store/service と結合テスト。残存 `PUBLISHING` を `UNKNOWN` に復旧し、読取照合だけで確定。自動再送しない |
| 重複抑止 | `AutonomousDuplicateStoreTest`、`AutonomousPostComparisonTest`。同一 DID の公開済み・公開中・不明の本文を transaction 内で比較。完全一致と NFKC/Unicode 空白一致を抑止 |
| 定期実行 | `AutonomousOpportunitySchedulerTest`、`AutonomousOpportunityWorkerTest` と SQL 結合。Clock/zone/上限/再起動/手動競合/停止/非同期 worker を検証 |
| 通知 | notice/store/Attention/pipeline テスト。固定 ID・再配送・順序逆転・確認解消・ACK と公開の分離を検証 |
| 操作面 | 認証付き `AutonomousHttpSmokeTest`、従来 Shell、軽量 CLI、Rust、React、Playwright。版・固定 route・サーバー切替・二重送信抑止を確認 |
| 既存構造 | Architecture/Catalog 回帰。循環依存・既存権限動作を維持し、新しい抑制設定や静的解析 baseline を追加しない |

過去の本文は比較だけに使い、自動で生成入力へ追加しません。重複判定は意味的類似性ではなく、
大文字小文字・句読点・単語境界を残した一致です。外部で削除された投稿もローカル記録からは消さず、
不明な投稿が存在しないと決めつけて再送しません。結果照合は現在取得できた固定レコードとの一致を確認します。
資料の許可取消は以後の取得を止める操作であり、送信済みモデル入力・既存ドラフトを回収しません。
ユーザーは生成済み本文も公開前に確認します。見送り理由の元データは非公開のままで、
選別・編集したコピーだけに資料の版管理と明示許可を適用します。

## 回帰の実行

JDK 25・Maven と client/terminal の開発依存を準備します。Windows は `./mvnw.cmd` を使います。
検証用 Bluesky 設定を無効にし、空の資格情報で実行してください。`live-e2e` は含めません。

```sh
./mvnw -Pfull test
./mvnw checkstyle:checkstyle spotbugs:spotbugs
./mvnw -f terminal/pom.xml test
cd client
npm ci
npm run typecheck
npm run lint
npm run format:check
npm test
npm run build
npm run test:e2e
cd src-tauri
cargo fmt --check
cargo test
```

静的解析はレポート生成の成功だけで合格とせず、`.github/ci/static-analysis-gate.ps1` で
Checkstyle/SpotBugs の実件数が共にゼロであることを確認します。
Java 整形は変更ファイルを対象とし、既存の別ファイルの整形差分を混ぜません。
全体回帰の最中に source/class を再コンパイルしません。
GUI はデスクトップ・モバイル双方の Playwright fixture で検証し、本文・投稿先・履歴・
見送り理由の選別と未許可コピーをスクリーンショットでも確認します。
PR の最新 head と実際のマージ SHA それぞれで、Java regression（最終集計を含む）、
Client regression、Java static analysis の全体成功を確認します。

## 将来の接続箇所

バイオリズム・気分・行動意欲・性格は、読み取り専用の内部状態 snapshot を
`AutonomousActionContext` と policy に渡す形で検討条件へ追加できます。
生成に必要な文体情報はそのうち公開可能と明示した部分だけを専用入力へ渡します。
通常 Memory/会話/Activity や資格情報をそのまま注入しません。
内部状態により一日上限、クールダウン、公開情報の許可、確認版、公開権限を緩和しません。

イベント駆動はイベントごとの一意な機会 ID と trigger を定義し、既存の
`AutonomousGenerationService.consider` を通します。trigger を増やす場合は永続 schema の
CHECK 制約と移行も更新し、同一機会の重複と定期・手動との競合を検証します。
イベント受信から直接公開 service を呼びません。

Bluesky 以外の行動には、内容モデル、妥当性検証、policy/生成、保存と状態、確認 UI/Shell、
行動固有の明示権限、transport と結果照合を追加します。今回の機能だけのための未使用の
汎用 action framework は作りません。
今回は内部状態の値・計算式、性格の自動更新、無人公開、返信・メディア・mention、
意味的な類似性検索、過去投稿の自動 prompt 追加、実アカウント疎通、新しい本人認証・OS 隔離を実装していません。
