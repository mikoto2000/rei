# 通常チャットの共通接続基盤 Phase 4

## 作業状態

実装とローカル検証を完了。基点は Phase 3 のマージコミット `44a01afde82400a3263fffb6ceb181e2444ee60b`。
PR の CI・マージ・マージ後検証は別途追跡する。実サービスへの推論要求は実施していない。

## 既存制約の分類

| 分類 | 現在の実装と維持する保証 |
|---|---|
| Responses 共通 | `ResponsesAdapter` がネイティブ options、モデル・接続の一致、retry=0、明示的な完了、完全な tool arguments を検証する。SDK が HTTP/SSE を扱う。 |
| 従来の OpenAI API キー経路 | `ResponsesProductionPolicy` は chat / output-limit-planner の承認済み構成を照合し、120 秒・encrypted・max-tools=36・low/default の固定条件を適用する。この経路の検証を一括解除しない。 |
| ChatGPT Plan | プロバイダー、OAuth、固定 resource、アカウント登録、stream=true、store=false を個別に検証する。既存の app-server に通常チャットの実行を委譲しない。 |
| ローカル接続 | プロファイルで API と認証を明示し、能力の supported / unsupported / unknown を使う。ローカルという理由だけでは能力を推測しない。暗黙の effort / tier / encrypted include を持ち込まない。 |
| モデル固有 | 明示した生成設定・メディア・構造化出力などをモデル能力と照合する。未確認の別モデルへ変更した際は元モデルの宣言を流用しない。 |
| Rei の安全性と整合性 | Run 受付時の immutable pin、遅延資格情報取得、送信先一致、redirect/retry 禁止、エラー本文の秘匿、履歴の owner/endpoint 一致、承認・Checkpoint を維持する。 |

## 公式仕様の確認

確認日: 2026-11-26。

- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference): Plan 用 OAuth bearer は public Responses resource に送信し、各要求で stream=true / store=false、terminal event を確認する。
- [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations): 明示 input 配列と instructions / developer、HTTP previous_response_id の不使用、生成パラメーターと hosted tool の制限を Plan 固有ポリシーとして検証する。

公式文書の確認は実接続試験ではない。

## 最初の TDD サイクル

Spring AI 2.1 の要求ビルダーが `reasoning.encrypted_content` を自動指定する。
従来の `ResponsesSdkBoundary` は plaintext の場合だけ typed SDK request から include を除去していたため、none では不要な include が送信された。

`ResponsesTransportContractTest` に encrypted / plaintext / none と call / stream の 6 ケースを追加。
Red は 6 件中 none の 2 件のみ assertion failure、error=0、skip=0。
修正は SDK 境界に再送方式を渡し、encrypted のときだけ include を維持する。
plaintext の応答内容の検証は別の判定として維持する。完成 JSON の汎用書き換えや、HTTP エラー後のフィールド削除・再送は行わない。
このテストは同時に effort / tier の不在、store=false、実際の stream 値も検証する。

関連検証: 通常の Maven test で ResponsesTransportContractTest、PinnedResponsesTransportTest、ResponsesReplayContractTest、ResponsesProductionGateTest、ResponsesProductionTransportTest の計 350 件が成功。failure / error / skip はいずれも 0。2026-11-26 12:49:05 JST 完了。これは Phase 4 全体の回帰完了ではない。

## 名前付き Responses プロファイル

`ResponsesProfilePolicy` を新設し、`PinnedChatModels` の名前付き Responses 経路から検証する。API の supported 宣言、profile / endpoint / credential reference の一致、認証方式、アカウント情報の分離、read timeout、出力上限・再送能力を検証する。
従来の `ResponsesProductionPolicy` は変更していない。

要求時は受け付けた Run の pin を保持し、利用する生成設定と tool capability を検証する。
出力上限はプロファイルに上限がない場合にだけ省略を許可する。上限が設定されている場合の必須条件と上限超過拒否を維持する。

所有する localhost サーバーに対し、共通モデルキャッシュから blocking / streaming と出力上限あり / なしの 4 通りを送信し、認証ヘッダー・送信 JSON を確認。RESPONSES が unknown / unsupported の 2 通りは送信前に拒否。
関連 187 テスト成功、failure / error / skip=0、2026-11-26 13:01:55 JST。
Checkstyle 違反 0、Spotless 3256 ファイル clean（同日 12:57:56）。最終全体ゲートは別途実施する。

## Plan の要求ポリシーと blocking 呼び出し

Plan の要求構築には固定の public resource、OAuth、非空のアカウント登録識別子、encrypted と supported の Responses / streaming / replay を要求する。
`call()` も既存の検証付き `stream()` を利用して集約する。Plan は completed 以外の終端を成功にしない。Namespace 実装前の flat tool 要求は拒否する。

SDK fixture の最初の正常系 2 ケースには SSE のデータ行に生の JSON 改行を入れる誤りがあり、SDK が拒否した。fixture のフレーミングだけを修正し、同じ payload と同じ期待値で再検証した。
Plan 3 件を含む関連 190 件が成功（failure / error / skip=0）、2026-11-26 13:13:02 JST。

実アカウントの登録識別子を取得する service 側のコールバックと OAuth の接続は次の工程で行う。この SDK fixture 成功を、通常チャットで Plan が利用可能になったという報告には使わない。

## 最終 HTTP 境界のサイズ制限

`PinnedWireLimits` はリクエストの完成したバイト列を上限付き sink で確認してから送信する。JSON の解釈・フィールド変更はしない。受信側は body source を包み、Content-Length の有無に依存せず、chunked / SSE の読み取りを上限で停止する。
既存の送信先・認証検証と non-replayable body を維持する。

`request_size_limit` / `response_size_limit` は固定診断で、再試行しない。本文と資格情報を診断に含めない。SDK が例外を包んだ場合も、この内部の安全な分類を保持する。

所有する localhost HTTP fixture の 6 ケース（送信上限超過、受信上限超過 call / stream、範囲内 call / stream、delta 到着後の受信超過）を含む関連 191 テストが成功。failure / error / skip=0、2026-11-26 13:52:14 JST。
送信超過は HTTP 到達 0 回、受信超過は 1 回で停止し、再送しないことを確認した。

## Run に保存する通信設定

名前付きチャットの接続先で使う設定は以下。外側の chat、補助 feature、従来の平坦設定に置いた場合は、設定名を示して拒否する。従来からある rei.llm.read-timeout は引き続き利用できる。

| 設定 | 既定値 | 保存先 |
|---|---|---|
| request-limit-bytes | 33554432（32 MiB） | Run transport.request_limit_bytes |
| response-limit-bytes | 67108864（64 MiB） | Run transport.response_limit_bytes |
| connect-timeout | 1 分（SDK 4.101 の既定値を明示固定） | Run transport.connect_timeout |
| read-timeout | rei.llm.read-timeout | Resolved endpoint.readTimeout |
| request-timeout | その接続先の read-timeout | Run transport.request_timeout / endpoint fallback |

サイズは 1～134217728 bytes。明示したタイムアウトは 1 ms 以上で既存の時間検証に従う。新しい Run では省略時のサイズ上限も保存する。通信上限は接続の資格情報を表す識別属性とは分けて検証し、設定が変わっても既に受け付けた Run の上限を保持する。

設定のコピー・不正値拒否・保存と復元を含む関連 63 テストが成功（failure / error / skip=0）、2026-11-26 14:01:12 JST。
設定から通信属性への変換は Factory に置き、設定クラスとの依存循環を避けた。通信設定 helper はプリミティブと Map の検証に限定する。

## タイムアウトと品質ゲート

名前付き接続は、要求全体の期限を受信停止の期限と別に保持する。SDK の要求ごとの指定で接続・受信の上限が拡大しないよう、HTTP 境界で保存済みの範囲へ収める。既存の長時間ストリーム用 interceptor は従来経路で維持し、名前付き接続は全体期限を解除しない構成を使う。

実 HTTP fixture で connect=2秒 / read inactivity=3秒 / request=4秒を確認。さらに共通キャッシュから、データが届き続ける SSE を request=0.5秒 / read inactivity=1秒で停止させ、タイムアウト診断・要求1回を確認した。OkHttp の全体期限超過が InterruptedIOException("timeout") になることは、使用中の 4.12.0 の bytecode でも確認した。

最終の静的解析で nullable な metadata getter の繰り返し呼び出しが指摘されたため、一度取得した値を検証する形に修正した。抑制や検証の削除は行っていない。

- 最終関連テスト: 208件成功、failure / error / skip=0（2026-11-26 14:50:56 JST）。1797 main / 1463 test ソースを通常の Maven test でコンパイル。
- Checkstyle: 違反0、Spotless: 3260ファイル clean、Spotbugs: bug=0 / analysis error=0（同日14:52:52）。
- アーキテクチャ: 6件成功（同日14:52:04）。予算・分類・ゲートを変更していない。
- 軽量 CLI / ランチャー: 36スイート205件すべて成功、failure / error / skip=0。verify と配布物ビルド成功（同日14:48:50）。

全回帰の最初の実行は上記 Spotbugs 修正のため中断した。成功件数には含めない。修正後の同じコンパイル済みソースに対し、-Pfull surefire:test で全回帰を最初から実行し、8773件（8761件成功、既定スキップ12件）、failure / error=0、BUILD SUCCESS を確認した。2026-11-26 15:16:49 JST 完了、所要23分09秒。テスト選択による限定はしていない。

## 後続 Phase の結合範囲

Plan の要求構築は fixture で検証した。既存 OAuth・保護 store からアカウント登録を解決する本番サービスの結合は Phase 5、Plan の namespace 付き tools は Phase 6 で行う。この Phase 単独では、Plan アカウント登録の検証を迂回して通常チャットを有効にしない。

## 未完了

- PR・CI・マージ・後片付け

実接続試験は未実施。所有する HTTP fixture と `.invalid` の送信捕捉を実サービスの動作確認とは扱わない。
