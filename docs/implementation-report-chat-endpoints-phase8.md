# 通常チャット接続基盤 Phase 8：共通履歴・入力・静的用途の分離

Phase 7 の通常マージ `fcb550cb97c13b0c6319f192fc9ef6a5ed6eaf52` を基点とする。OpenAI API、ChatGPT サブスク、ローカル LLM は既存の共通 Run・接続スナップショット・承認・保護メッセージを使用する。

## 完了済み履歴の引き継ぎ

接続先が異なる完了済み Run では、表示用の user・assistant 履歴に加え、保護された履歴から完了した操作名・引数・結果を取り出す。結果は「完了済みの過去のデータ」として JSON を含む通常の assistant メッセージにする。実行可能な tool call、reasoning、接続先固有のメタデータを新しい接続先へ転送しない。

画像・ファイルはデータ、MIME、名前を引き継ぎ、接続先固有のファイル ID を除く。転送先の入力 Capability と、実際の履歴・添付を含む推論送信承認を別途検証する。元の payload・参照は変更しない。転送先の byte 配列を変更しても保存済みの原本は変化しない。

完了状態、Project・ルート・Session、保護参照、履歴件数・順序、call/result の ID・名前と完結性を確認する。不完全な transaction や欠落した参照を別の形式に推測変換しない。失敗・キャンセル・実行途中の Run は公開しない。既に要約で覆われた履歴は既存の cursor を維持する。

同じ接続先の Chat Completions 応答に native reasoning が含まれていても、Responses 専用の encrypted replay 検証を適用して次の要求を妨げない。保存された本文と provenance は保持し、通常の reasoning 再送は無効にする。現在の設定済み CC 経路の REASONING_REPLAY は引き続き未対応である。人工的な確認済み pin の SDK 試験を、実運用の対応確認とは扱わない。

## 画像・ファイル・生成設定

名前付き接続の入力は両 API で共通に検証する。画像は PNG・JPEG・GIF・WebP、ファイルは PDF・plain text を対象とし、CC のファイルは inline PDF に限る。未確認・未対応の VISION / FILE_INPUT、相対 URI、不正な data URI、音声・動画などは送信前に拒否する。アップロードや Rei による外部 URL の取得は追加しない。

media 本体の上限に加え、既存の実リクエスト・承認入力・保護メッセージのバイト上限が適用される。大きな添付は拒否される場合がある。fixture の小さな画像・ファイルでの送信形式検証を、実モデルによる読み取り成功や最大サイズの互換性確認とは扱わない。

`rei.llm.features.chat.endpoints.<name>.generation` に温度、top-p、reasoning-effort、response-format などの対応設定を追加する。設定を canonical な JSON 値へ変換し、Run の受付時に固定する。API に合うフィールド、独立したサーバー・モデルの対応宣言、値の範囲、整数の正確性、出力上限を検証する。未知・制御用フィールドや flat/helper への誤配置は明示的に拒否する。

明示した OpenAI API の CC 接続は既定の出力上限を `max_completion_tokens` へ解決する。互換接続と既存 flat 設定の既定動作は維持する。Responses は `reasoning.effort`・`text.format` へ、CC は各 API の typed フィールドへ変換する。ChatGPT の未対応パラメーターや store=false / stream=true などの条件は維持する。

SDK 契約を確認した Plan の reasoning-effort・structured-output・画像・ファイルを adapter 側で表現できるようにした。実際の利用にはサーバー・モデルの独立した宣言が必要であり、未確認のモデルを対応済みとしない。CC も Responses と同様、API と Streaming が未確認なら受付・復元を拒否し、無効な Run 接続を残さない。

## 補助機能と自動実行

通常チャットの動的選択を、既存の静的モデル設定へ流し込まない。要約、Work Context、音声補正、スキル選択、活動、プランナーなどの provider は静的設定を継続する。

Scheduler と非 private な Goal 実行には独立した実行入口を設けた。従来の静的 chat・共通設定を解決して Run に固定し、通常チャットで選択中のプロファイル・モデル・ChatGPT アカウントを継承しない。静的設定を解決できなければ理由を示して停止し、動的選択へフォールバックしない。静的設定の明示モデルを維持し、資格情報は従来の送信直前の取得を使う。

予算予約、所有者、承認待ち、キャンセル、timing、後片付けは既存の実行処理を共有する。保存済みの静的接続は現在の選択より優先し、URL・認証・設定世代などの不一致を拒否する。独立入口へ通常チャットの named Run が渡された場合は用途を変更せず停止する。private secretary の既存経路は変更しない。

## 検証記録

| 対象 | 結果 |
| --- | --- |
| native CC reasoning | 次要求のエラー 2 件を再現して修正。関連 200 件成功、provenance・明示 replay 境界 22 件成功 |
| 完了した操作の共通履歴 | 操作・結果が欠落する assertion failure 1 件を修正。関連 94 件と不正履歴の境界試験が成功 |
| 入力 Capability と typed media | 未確認入力の送信を再現して修正。両 API・認証の関連 382 件、設定宣言の関連 90 件成功 |
| 名前付き生成設定 | 設定無視の assertion failure 4 件を修正。関連 72 件、整数・設定スコープ・Provider の境界 121 件成功 |
| Plan の生成形式 | 独立宣言が必要な route Capability の assertion failure 2 件を修正。関連 123 件成功 |
| 添付履歴 | 画像・PDF の欠落 2 件を修正。関連 130 件成功。取り込みには既存の inline file resolver と実 SQL を使用 |
| CC 受付・復元 | 未確認 API / Streaming の assertion failure 8 件を修正。関連 77 件成功 |
| 自動実行の分離 | 動的選択の継承 2 件と明示静的モデルの置き換え 1 件を修正。関連 163 件成功、owned loopback SDK で無認証送信も確認 |
| 最終の履歴・補助機能契約 | 174 件成功。ローカル Responses → Plan/encrypted → ローカル CC、都度の SQL 承認、拒否時の未送信、操作 1 回、原本保持、添付境界、16 機能 × 2 設定を含む |
| 軽量 CLI | 205 件と配布物生成が成功 |
| 最終静的解析 | Checkstyle 0 / SpotBugs 0 / 解析エラー 0、Spotless 3306 ファイルで成功 |
| 全回帰 | 9229 件中 9217 件成功、既定スキップ 12 件、失敗・エラー 0。1444 suite の全 XML が今回更新され、全 testcase の一意性とスキップの全 ID・理由を既存許可リストへ照合済み。実行時間 23:11 |
| 依存関係 | 6 種の正規化した依存集合が Phase 7 と完全一致。C-business 2914 / C-wiring 799 / D 402 / E 1077、A・B 0 |
| PR・CI・マージ・後片付け | 最新 head の CI、通常マージ、実マージ SHA の push CI、所有 worktree の通常削除まで追跡する |

初期の fixture のコンパイル・JSON/Streaming 形式・verify 更新不足による失敗は成功扱いにしていない。初回全回帰は既存の接続固定 fixture の CC/Streaming 宣言不足による 11 error を検出し、自身のビルドだけを停止した。実際の対応宣言を追加して関連 99 件を成功させ、最終全回帰を最初から再実行した。静的解析の nullable getter と整数比較の指摘は、抑制せず実装修正で解消した。

実アカウント、実ローカルモデル、実 app-server による推論は行っていない。SDK は所有する loopback または DNS 前 interceptor を使用し、業務操作の実体は fixture 内に限定する。依存分類・予算・抑制・既定スキップは変更していない。Phase 9 の GUI と Phase 10 の互換性マトリクス・利用者向け設定例・最終検証は後続工程とする。
