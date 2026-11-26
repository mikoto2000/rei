# 共通チャット接続 Phase 6 実装・検証報告

## 実装

OpenAI API、ChatGPT Plan、ローカル LLM の名前付き接続に、共通の保護入力と推論送信承認を適用する。Rei が既存の通常ループ、動的ツール検索、ツール権限、保存、続行キューを所有する。通常チャットを app-server のエージェントへ委譲しない。

- ChatGPT Plan は公式 SDK 4.101 の型付き namespace `rei` に既存 function 定義を格納する。要求ごとに公開名を固定し、call/result の namespace・名前・ID・引数・所有者・接続を照合する。名前の prefix 除去や別名解決は行わない。
- native function call の未確認フィールド、別 namespace、未公開ツール、不正 ID、不完全な引数を拒否する。部分応答を実行・確定保存しない。Plan の個別関数指定 `tool_choice` は契約未確認のため送信前に拒否し、`auto` / `none` / `required` を保持する。
- 既存 ToolDiscovery のローカル `searchTools` と要求ごとの公開スナップショットを使う。Plan と無認証ローカル Responses で、検索後の要求から専門ツールを公開し、実行結果を含む継続推論を検証した。ローカルへ Plan namespace を送らず、資格情報を取得せず、認証ヘッダーも送らない。
- CONVERSATION はツールを送信しない。READ_ONLY / EXCLUSIVE の既存 ToolPermissionGuard は維持する。親の Plan 要求枠はツールの実行前に解放する。
- 名前付き Chat Completions / Responses の入力を同じ Run 所有者と受理済み接続へ結び付ける。旧フラット設定と静的 helper の経路は維持する。
- Chat Completions の既知の SDK metadata は、型を確認した、サイズ・深さに上限がある JSON 文字列として既存 codec に渡す。保存形式で任意の SDK オブジェクトの直列化を許可しない。

## 推論送信とツール実行の権限

推論送信は、既存ポリシーの NETWORK_WRITE と EXTERNAL_SIDE_EFFECT を用いて判定する。管理者の拒否を承認済み grant より優先し、音声と明示 full-auto の既存規則を適用する。ツール名の再分類で推論送信権限を変えない。送信の許可は callback の実行許可を与えない。

承認が必要な場合は、送信前に既存の WAITING_APPROVAL へ移り、HTTP・要求枠・資格情報取得へ進まない。内容の固定には system、会話履歴、media、tool 定義、tool 結果を含める。Run 所有者・接続・モデル・アカウント登録世代・実際の内容 digest と目的 `inferenceExport` を既存 SQL 承認に結び付け、一度だけ消費する。

待機には架空の tool call を作らず、既存の保護メッセージ参照を使う。保存前と復元時に内容 digest を再計算する。承認受付前に本文を検証し、短い承認 transaction 内で文書が検証済みのものと完全一致することを再確認する。復元・キュー投入にも同じ目的と所有者の照合を適用する。既存ペイロード保存の transaction 規則は変更しない。

`/approval show` と project-scoped `GET /api/v1/projects/{projectId}/approvals/{id}/inference` で、実際の保護済み本文・parts・公開ツール定義・送信先・digest を確認できる。表示は権限を付与・消費せず、一覧には本文を展開しない。

承認待ち前の推論予約は Saved.inferencePending で区別する。実 ChatExecutionService の 3 モードで、未承認時 HTTP 0 回、選択変更後の明示承認でも元の接続に HTTP 1 回、予約推論回数 1 のままとなることを確認した。通常の tool 続行と Checkpoint の既存予算規則は維持する。ContextAssembler 有効・無効 × 両 API × 3 モードの送信前待機も確認した。

## 能力と制約

Plan の実装側で対応済みとする能力は、契約試験済みの RESPONSES / STREAMING / FUNCTION_CALLING / TOOL_NAMESPACE / REASONING_REPLAY の 5 つだけである。サーバー・モデルの独立した宣言と交差して利用可否を決める。未確認のサーバーやモデルを対応済みにはしない。

Plan の固定公開送信先、OAuth、stream=true、store=false、encrypted replay と既存の禁止パラメーター・Hosted tool 制約を維持する。旧 ResponsesProductionPolicy の閉じた本番条件は変更しない。無認証ローカル Responses は Plan の登録・認証に依存しない。

実アカウント、実ローカル LLM、実 app-server の推論は行っていない。SDK の DNS 前 interceptor と所有する loopback fixture による契約検証であり、実モデルとの互換性の確認ではない。承認・Checkpoint・再起動・障害復旧の拡張は Phase 7、API 間の会話引き継ぎと media / metadata の追加互換性検証は Phase 8、GUI は Phase 9、実サーバー互換性マトリクスは Phase 10 で続ける。

## 検証記録

| 対象 | 結果 |
| --- | --- |
| namespace の未確認フィールド | Red: 26 件中 5 assertion failure、0 error → Green: 関連 38 件成功 |
| 実 ToolDiscovery と既存 replay | 212 件成功、既存 replay 169 件を含む |
| Plan 能力、両 Responses、組み立て済み入力の待機 | 137 件成功 |
| 静的解析修正後の関連検証 | 141 件すべて成功。不要な補助型への新規依存も実装で除去し、全 6 依存集合が既存予算と一致 |
| 全回帰 | 9047 件中 9035 件成功、既定スキップ 12 件、失敗・エラー 0。1440 suite の全 XML が今回の実行で更新され、9047 testcase とスキップの全 ID・理由を既存許可リストへ照合済み。初回は静的解析修正のため中断し、成功扱いにしていない |
| 静的解析 | 初回 SpotBugs 12 件を実装修正し、最新の補助型整理後も Checkstyle 0 / SpotBugs 0 / 解析エラー 0、Spotless 3300 ファイルで成功 |
| 軽量 CLI | 全 205 件と配布物生成が成功。初回は変更のない DetachedBackendTest の作成直後 PID ファイルの空読み取りで 1 error、再検証で成功 |
| PR / CI / マージ / 後片付け | 本報告はローカル検証時点。最新 head の必須 CI・レビュー・ルール確認後、通常マージ・実マージ SHA の push CI・専用 worktree の通常削除まで追跡する |

静的解析の抑制、分類・ルール変更、スキップ追加、検査対象の縮小は行っていない。共有サービスは既存と同様に操作を関数へ委譲し、null 確認は一度取得した値に対して行う。

## 個別依存レビュー

`docs/architecture/README.md` の個別レビュー手順に従い、次の 2 ペアだけを C-business に手入力する。

- `dev.mikoto2000.rei.core.policy.InferenceExportRequest -> dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration`：定義と確認属性を固定する際も、既存 callback の配信権限属性を保持するために必要。別の callback 権限機構を作らない。
- `dev.mikoto2000.rei.core.policy.InferenceExportGuard -> dev.mikoto2000.rei.core.chat.AgentRunContext`：既存 SQL 承認と同一の Project / root / Session / Run / mode / source / voice 所有者を使い、別 Run への承認流用を防ぐために必要。mode/source の不要な列挙型依存は追加しない。

依頼された Phase 6 の送信権限分離と Function Calling の範囲で、両ペアを PR のレビュー対象に明示する。C-business は 2912 → 2914、C-wiring 799 / D 402 / E 1077 / A 0 / B 0 は維持する。生成レポート全体のコピーや予算の自動拡大は行わない。静的解析の修正時に検出された補助型から所有者への余分な依存は、予算を増やさず実装で除いた。
