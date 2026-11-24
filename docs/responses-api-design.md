# Rei の Responses API 対応設計

作成日: 2026-11-24
状態: Phase 0・1 はマージ済み。Phase 2 の暗号化推論再送・既存ツールループ統合を検証中。末尾の改訂契約と進捗台帳を参照。DB移行・本番有効化は未実装。
基準コミット: `18894acb`（Run ごとの動的ツール選択）。

## 前回調査時点の作業範囲（履歴）

未コミットだった OpenAI 互換性修正34ファイルを基準コミットへ戻した。取り消した変更は `target/tool-audit/pre-responses-compatibility.patch` に退避している。`max_completion_tokens` への一律変更、Work Context の思考設定の無効化、ツールなし要求14経路の `tool_choice` 変更、対応するテスト・説明の変更が対象。

コミット済みの基本31件＋`searchTools`、専門153件、Run別の公開状態、保持・LRU・リクエストスナップショットは維持する。[動的ツール選択設計](tool-discovery-design.md) と [分類表](tool-discovery-classification.csv) を変更しない。

既存の未追跡ファイル `chat-endpoint-switching-design.md` と `review/` は変更・削除しない。外部 `application.yaml` はこの作業で巻き戻さない。現在のモデル選択は利用者が変更したものであり、外部設定全体には認証・他機能の設定も含まれるためである。現在のモデル・推論・temperatureと動的ツール選択の設定は、移行時の入力として扱い、自動的にResponses向けへ転用しない。

前回の設計調査では JAR の作成・配置・プロセス再起動・実 API 呼び出しをしなかった。今回の実装では専用worktree内のビルドと自動テストを行い、本番配置・再起動・未承認の実API呼び出しは行わない。ロールバック後のソースは暫定修正前であり、現在の OpenAI 設定で動作成功する状態へ修正したという意味ではない。

## 決定する方針

- OpenAI と DeepSeek のエージェント実行には、共通の Responses API 経路を用意する。
- API形式は設定で選択する。ホスト名・モデル名・HTTP 400の文言から接続先や代替形式を推測しない。
- Responses非対応のローカルモデル等のため、既存 Chat Completions 経路を残す。自動的にAPI形式を切り替えるfallbackは作らない。
- 会話履歴と継続状態は引き続きReiが管理する。`previous_response_id`・サーバー側conversation・サーバー側履歴保存に依存しない。
- ツールの権限・承認・結果配信・実行・キャンセル・予算・再開は既存の処理を使う。SDKの別ループと二重に実行しない。
- 通信・SSE解釈は Spring AI 2.1.0 と同梱OpenAI SDKを再利用する。新しいHTTPクライアントや汎用エージェント基盤を作らない。
- 推論の暗号化payloadと平文payloadの差を、明示した再送方式で扱う。暗号化データを解読したり、平文を暗号化データに見せかけたりしない。
- 基本31件＋検索と専門153件の分類・権限を維持する。Responses対応を理由に全ツール公開へ戻さない。

## 調査した既存コードと実際の不足

依存版は `pom.xml` の Spring AI **2.1.0**。ローカルの同版JAR・source JAR・自動構成メタデータを照合した。以下は将来版への期待ではなく、この版の実装を調べた結果である。

| 箇所 | 確認した動作と対応 |
| --- | --- |
| Spring `OpenAiResponsesChatModel.call/stream/buildRequestPrompt` | 既存の `ChatModel` を実装。毎回Prompt全体を送信し、ツール実行ループは持たない。native Responses optionsはモデルdefaultsと再マージされない。完全な要求optionsをRei側で構成する |
| Spring `ResponsesRequestBuilder.build/includes/tools` | `max_output_tokens`、`reasoning.effort`、`store:false`、暗号化推論のinclude、既定 `strict:false` を生成する。Hosted Toolsは初期対応では無効とする |
| Spring `ResponsesItemMapper.toGeneration/assistantItems/reasoningItem` | 順序付き `MessagePart` とAPI itemsを対応付ける。functionの `call_id` は `ToolCall.id`、item自身のidはpart属性へ分離される |
| 同 `reasoningItem/reasoningPart` | `encrypted_content` のないReasoningPartはnative再送から除外される。DeepSeekの平文推論に対する補完が必要 |
| Spring `ResponsesStreamAssembler.apply` | semantic SSEを集約し、terminal eventでfinish reasonとusageを確定する。最終itemとdeltaの二重計上を防ぐ実装がある |
| `LlmModelProvider.chatOptions/chatOptionsBuilder/createFeatureModel/captureModel` | options戻り値と生成処理が `OpenAiChatOptions` / `OpenAiChatModel` 前提。接続、生成上限、記録wrapperをAPI形式ごとに構築する必要がある |
| `LlmChatClientProvider.createChatClient/parallelChatClient`、`AiConfiguration.defaultChatOptions/chatClient` | 両生成経路と機能別クライアントを対応する。片方だけの変更では既存optionsやcallbacksが再混入する |
| `ChatExecutionService.executePrompt`、`StagnationChatModel.prepare/iteration/observe`、`ToolLoopSupport.executeResumable/executePendingWithinBudget` | 現行の実行境界。応答を得た要求のスナップショットでバッチ全体を検証し、既存loopを維持する |
| `ToolDiscovery.Session.endpointFingerprint/project/validatePending` | fingerprintはChat Completions固有型のURL/timeoutを読む。Responsesの接続情報、API形式、再送方式も同じRun固定構成に含める |
| `JdbcConversationMemoryStore.saveAll/findByConversationId` | ツール呼び出しとツール結果を保存対象から除外し、他のメッセージも本文のみを保存する。完全なprotocol履歴の正本にはできない |
| `ContextHistoryAdvisor.before/historical` | 表示ログ・turnから履歴を再構築し、assistantを本文だけで作り直す経路がある。Responsesのpartsを失わない経路を追加する |
| `ApprovalContinuationStore.StoredMessage/save`、`RunContinuation.PendingTools` | 本文、calls、成功済み結果、配信属性等を保存するが、元assistantの推論parts全体を保持しない。元応答の参照を追加する |
| `PersistentCheckpoint`、`PersistentCheckpointService.resume*/start` | checkpointは実行証拠を保持する設計で、推論traceや生の結果を格納しない。この方針を維持し、protocol履歴への参照を使う |
| `ContextAssembler.assemble`、`ConversationSummaryRepository`、`LlmConversationCompressor` | 圧縮済み範囲と既存の要約予算を維持し、途中のtool transactionを分割して圧縮しないよう拡張する |
| `ChatStreamTimeoutInterceptor.intercept`、`CaptureInterceptor.intercept` | `/chat/completions` のみを対象にする。Responsesのread inactivity timeoutと記録にも明示対応する |
| `ShowUiSdkRequestInterceptor.intercept` | Chat Completions固有の書き換え。Responsesに単純拡張せず、その機能の明示的な旧API設定を維持する |
| `OutputLimitDetector.isOutputLimitReached`、`AgentEventChatModel` | SDKは `incomplete/max_output_tokens` を `LENGTH` に変換する。既存の出力上限検知と累積usageの計上を再利用して境界テストする |

「履歴管理はすでにReiが担当している」ことと、「現在の全保存経路がResponses再送情報を保持している」ことは区別する。変更するのは後者の保存内容・復元経路であり、会話管理の所有者ではない。

## 依存実装の適合調査

調査用fixtureを `target/tool-audit/ResponsesReplayInspection.java` で実行した。HTTP interceptorが合成応答を返し、外部通信と業務ツール実行は行っていない。

| SDKに渡した履歴 | 次要求の推論item数 | 結果 |
| --- | ---: | --- |
| 平文ReasoningPartをnativeのまま再送 | 0 | DeepSeek対応をそのまま宣言できない |
| 平文ReasoningPartを送信時だけprotocol itemへ変換 | 1 | 平文・item idを再送可能。function call/resultも残る |
| 暗号化payload付きReasoningPartをnative再送 | 1 | SDKの標準経路で保持可能 |

この調査は非streamingの小さなfixtureであり、本番の平文推論対応や永続化が実装済みという意味ではない。streaming、再起動、承認中断、圧縮を含む受入条件は後述する。

## 設定と接続先の固定

Spring AI 2.1.0の既存設定を利用する。

```yaml
spring:
  ai:
    openai:
      base-url: https://api.openai.com
      api-key: ${OPENAI_API_KEY}
      chat:
        api: responses
      responses:
        model: gpt-5-mini
        reasoning-effort: low
rei:
  llm:
    max-output-tokens: 16384
    tool-discovery:
      enabled: true
    responses:
      reasoning-replay: encrypted
```

`spring.ai.openai.chat.api=responses` と `spring.ai.openai.responses.*` は実在するSpring設定である。上例の `rei.llm.responses.reasoning-replay` は新設する設計項目で、現行Reiではまだ使えない。

生成上限は既存 `rei.llm.max-output-tokens` と機能別 `max-output-tokens` をRei経由要求の正本とし、Responsesでは `max_output_tokens` に対応付ける。Springのモデルdefaultsに生成上限がある場合も、Reiの要求optionsで決定した実効上限が最終HTTPに出ることを検証する。`spring.ai.openai.chat.options.*` はChat Completions用なので、Responsesへ暗黙にコピーしない。

機能別 `LlmProperties.Server` には次を追加する。既存のcopy処理にも含め、不変の実効接続構成を作る。

| 項目 | 意味 |
| --- | --- |
| `api` | `chat-completions` / `responses`。未指定の共有接続はSpringの既定モデルのAPIに従う。既存の独立したcustom serverは互換性のため旧APIを維持し、新形式は明示指定する |
| `responses.reasoning-effort` | 提供元が認める値。未指定なら勝手に `none` や `high` を付けない |
| `responses.reasoning-replay` | `encrypted` / `plaintext`。共有接続ではroot設定を継承し、独立接続では明示を要求する |
| `max-tools` | 接続先のツール上限。動的公開上限と許可fallback候補の上限を含めた最小値を使う |

DeepSeek公式APIをchat用に選ぶ設計例:

```yaml
rei:
  llm:
    features:
      chat:
        api: responses
        base-url: https://api.deepseek.com
        api-key: ${DEEPSEEK_API_KEY}
        model: deepseek-flash
        max-output-tokens: 16384
        max-tools: 128
        responses:
          reasoning-effort: low
          reasoning-replay: plaintext
```

この機能別API・responses設定も新設案であり、今の実装へ貼り付けるための設定例ではない。公式API以外の提供サービスでは、接続先の仕様を別途検証する。

temperature/top-pは明示された場合だけ送る。モデルが許可しない組み合わせは事前の設定検証または具体的なAPIエラーとして扱い、HTTP 400を見て値を順番に変えて再送しない。`enable-thinking` / `chat_template_kwargs` はResponsesの思考制御へ流用しない。旧設定がResponses機能に残っていれば移行診断で通知し、無効な設定を黙って成功扱いしない。

Run受付時にAPI形式、endpoint世代、URL、モデル、認証のサーバー内参照、再送方式、生成設定、上限、許可fallbackを固定する。APIと再送方式をモデル/clientキャッシュのキーおよび保存状態のfingerprintに含める。進行中Run・承認続行の途中では切り替えない。[接続先切り替え設計](chat-endpoint-switching-design.md) の選択UIやコマンド自体は今回の実装範囲に含めない。

## 通信とoptionsの責務

`LlmModelProvider.chatOptions` の戻り値と各呼出元の `Function` / `Supplier` を、Chat Completions専用型から `ToolCallingChatOptions` へ変更する。モデル生成時は、選択APIに対応するnative optionsを常に作る。汎用ChatOptionsを渡してSDKの暗黙変換だけに頼らない。

生成上限の設定、tool-free化、raw option検証は既存のproviderまたは小さなoptions補助メソッドへ集約する。`maxTokens` / `maxCompletionTokens` / `maxOutputTokens` の選択を全呼出元で繰り返さない。API形式による変換は必要だが、OpenAI/DeepSeekというベンダー名で生成処理を分けない。

対象にはchatだけでなく、スキル選択、Work Context、Sleep、要約、音声補正、論文、関心抽出、レビュー、子エージェント、診断を含める。既存の独立した画像・音声APIは変換対象外。ShowUI等の専用書式を使う機能は、明示された旧APIのまま検証する。

Responses要求の不変条件:

1. `max_output_tokens` だけを出力上限として送る。`max_tokens` / `max_completion_tokens` は混入させない。
2. ツールなし要求では `tools` と `tool_choice` を両方省略する。明示callbacksなし・既定tools拒否・raw tools拒否の三点を維持する。
3. ツールあり要求では `ToolCatalog` の定義とcallbackだけを使う。名前、件数、実効schema、strict設定を送信直前に検証する。
4. 初期対応のfunction schemaはSDK既定の `strict:false` を維持する。既存184件をstrict用に作り直すことを移行の前提にしない。
5. `store:false` のstateless経路を使い、毎回Reiが選んだ文脈全体を送る。会話IDをサーバー側継続IDとして使わない。
6. Hosted Toolsは初期対応で無効。SDKで定義を追加したり、Reiの権限・実行loopを通さず処理を始めたりしない。
7. Responses型の `extraBody` も検証する。`input`、`instructions`、`tools`、`tool_choice`、`store`、`previous_response_id`、`conversation`、生成上限、推論設定をraw JSONで上書きできないようにする。正規設定と内部の再送変換を、利用者のextraBodyから分離する。

## 推論項目の再送

OpenAIの暗号化推論とDeepSeekの平文推論は、同じResponses APIでも保存・再送の内容が異なる。共通の通信を使い、必要な差だけを小さな `ResponsesReasoningReplay` 変換に置く。設定の `reasoning-replay` と保存payloadの接続先世代を入力とし、URLやモデル名から推測しない。

### encrypted

SDKの `ReasoningPart.payload`、item id/status、summary、TextPartのphase、ToolCallPartを保持してnative経路へ渡す。SDKが要求する `include: reasoning.encrypted_content` を維持する。opaque payloadは変更・解読・要約しない。別接続先・別API由来のpayloadを再送しない。

### plaintext

永続化する正本は平文ReasoningPartとその属性のままにする。送信viewを作るときだけ、完全な平文とitem id/statusから `reasoning` input itemを生成し、SDKの `UnknownPart` によるschema-agnostic再送経路へ渡す。

`OpenAiResponsesMetadata.PROVIDER` はベンダー識別ではなくAPI形式の識別であることをsourceで確認した。このprotocol tagを使うが、実際の再送許可は別途、保存payloadの所有者と固定endpoint世代で検証する。平文を `encrypted_content` へ入れる実装は禁止する。原本のメッセージや暗号化再送方式のRunは変更しない。

変換対象は既知のReasoningPartに限定し、本文の命令・metadataの任意文字列・未知itemを推論に昇格させない。必須idがない、平文が欠けている、対応外の構造や出所なら、黙って除去せず再送検証で止める。SDKのunknown item変換失敗時のログだけに頼らず、Rei側で変換可能性と最終itemsを検証する。

非streamingの変換可能性は調査済み。streamingでの全文復元、複数reasoning item、未知属性、プロセス再起動の受入条件を満たすまでDeepSeekの推論ON対応を有効化しない。この限定変換で扱えない仕様が見つかった場合はSDK修正・依存更新を再設計し、HTTP interceptorでbodyを雑に差し替える回避策は採らない。

## 履歴・保存・再開

### 保存する情報

再送に必要なデータを、順序を持つversion付きの `StoredModelMessage` として表す。Javaクラス名による任意デシリアライズはせず、許可したpart型と明示スキーマを使う。

- role、message id、モデル要求との対応、所有者・接続先世代・API形式・再送方式。
- TextPartの本文、item id/status、phase。`commentary` と `final_answer` を同一扱いにしない。
- ReasoningPartの平文・summary・opaque payload・属性。暗号化blobは完全一致で保持する。
- ToolCallPartの名前、完成した引数、item id、**call_id**。item idとcall_idを混同しない。
- ToolResponseMessageのcall_id・名前・実際にモデルへ配信した結果と `ToolResultMetadata` の参照。
- 対応可能なUnknownPartのprotocol tag・raw JSON・属性。サーバー実行済みitemをReiのfunction callへ変換しない。

画面・検索・要約用の本文投影と、次要求へ戻すためのprotocol情報を区別する。本文投影から推論やcall_idを推測して復元しない。

### 保存先と正本

既存の表示用JSONLログと互換memoryの契約は維持する。tool messageを除外する現在の `SPRING_AI_CHAT_MEMORY` を、完全再送の正本として使い続けない。

`memoryConsolidationDataSource` に、再送情報だけを持つ小さな追記型 `model_message_payloads` 表を追加する設計とする。保存項目はpayload id、schema version、project/root/conversation、起点run、要求IDとメッセージ順、前の文脈anchor、endpoint世代、API形式、再送方式、順序付きpayload、hash、作成時刻。要求IDと順序の組を一意にし、同じ保存を繰り返しても重複させない。業務の完了statusや第二の処理台帳は持たない。会話ログ・turn・承認継続はpayload参照を保持し、表示本文とは内容hashで対応を検証する。

この表を追加する理由は、既存の本文ログ、互換memory、checkpointの役割を壊さず、opaque dataとモデル向け配信内容を一度だけ保存するためである。通常ログやcheckpointの人間向け証拠欄へ生の推論traceを混ぜない。既存の所有者スコープ・データディレクトリ・保持/削除操作に追従させる。

`SqliteConversationTurnStore` は `StorageDatabase`、承認継続は `memoryConsolidationDataSource`、会話ログはJSONLを利用する。これらが一括トランザクションになるとは想定しない。入力と完成応答のpayloadを先に永続化し、次に既存の継続文書へ参照を確定し、その後に次の副作用へ進む。表示ログのappendは現在失敗をwarnにするため、それをprotocol保存成功の判定に使わない。JSONLへの参照追記が失敗しても、要求ID・起点Run・anchorから正本を照合できるようにする。対応する継続参照の保存失敗では、ツール実行前に止める。

保存成功はツール実行前の条件とする。完成したモデル応答を永続化してから、call batchを権限検証・実行する。保存失敗で本文だけの履歴へ落として続行しない。payloadのサイズ上限を設け、超過は副作用の前に明示失敗とする。値の初期案は1メッセージ8MiBで、推論ON fixtureの測定後に確定する。暗号化・平文を途中で切って上限へ収めない。

### 既存保存境界への接続

| 保存境界 | 対応 |
| --- | --- |
| 通常会話 | `ConversationLogStore` / `ConversationTurnStore` へpayload参照を関連付ける。`ContextHistoryAdvisor` は参照があれば完全メッセージを読み、旧ログは本文互換で読む |
| メモリ | `ConversationMemoryAdvisor` / `JdbcConversationMemoryStore` は表示・互換用途を維持。Responsesを履歴復元する場合はprotocol参照を優先し、二重に同じメッセージを挿入しない |
| 承認継続 | `StoredMessage` と `PendingTools` に元assistant payload参照を追加する。成功済みprefixと残callを同じ元応答へ結び付ける |
| checkpoint | 実行証拠と業務台帳は既存のまま。選択されたprotocol履歴範囲への参照・hash・接続先情報を持ち、推論全文をcheckpointへ直書きしない |
| 子エージェント | 親の実行権限・公開済みツールを継承しない。子固有のRun構成とpayloadスコープを使う |

payloadは参照中の承認・checkpoint・有効な履歴範囲がある限り削除しない。削除・圧縮では参照整合性を検証し、必要payloadの消失を「本文しかないが継続可能」と扱わない。参照のGCは既存の削除操作へ組み込み、独立したスケジューラーは新設しない。

業務ツールの実行後に結果payloadの保存が失敗した場合は、副作用をなかったことにしたり自動再実行したりしない。既存の操作台帳と不明状態の照合経路を使い、正本で成功が確認できる結果だけを再配信する。モデル応答の事前保存失敗と、実行済み操作の結果保存失敗を別の回復経路として扱う。

### 旧データ

旧完了会話はテキスト履歴として読める。新しいユーザー入力から新規のResponses実行を始めることは許可する。失われている過去の推論内容を生成・捏造しない。

一方、旧APIで保存された未完了callや承認を、新APIの応答だったことにして実行しない。元のAPI・モデル・権限で続行できる場合だけ旧経路を使う。それができない場合は、既存の照合・完了・取消を経た明示的な新Runへの引継ぎが必要である。成功済み操作は再送しない。

## 文脈圧縮と予算

圧縮単位は、ユーザー入力、モデル応答の推論・文章・function calls、対応するfunction outputsを含む完了したtransactionとする。未完了call、承認待ち、成功済みprefix、結果不明のtransactionの一部だけを圧縮しない。残したcallに対応するresultがなくなる、resultだけ残る、推論itemを別の応答へ付け替える状態を拒否する。

完了単位を要約へ置換する場合、その単位全体を除いた新しい文脈として扱う。元の暗号化blobを要約文に貼ったり、旧call_idの再利用で継続を装ったりしない。圧縮済み範囲、payload参照、`throughSequence` を一貫して確定する。失敗した要約では既存カーソルを進めない。

推定入力には本文以外のparts、ツール定義、モデル向け結果投影も含める。opaque dataを0 tokenとして扱わず、実際の `usage` と推論tokenを観測して見積もりを調整する。サーバーの実測usageを課金・Run予算の正本とし、streamの累積usageを足し合わせない。推論tokenをcompletion totalへ二重計上しない。

モデルの `max_output_tokens` は本文と推論の生成枠として扱う。本文のJSON上限と生成全体の枠は既存要約・抽出予算と合わせて設計し、thinking ONで本文が出る前に生成枠を使い切る事例を受入条件に含める。

## ストリーム・動的公開・実行境界

処理順は次のとおり。

1. 固定したRun構成と選択文脈から、APIに合う完全なnative optionsを作る。
2. `ReviewToolProfile`・実行モード・復旧制約を適用し、`ToolRequestAssembler` で不変の定義snapshotを確定する。
3. replay方式に合う送信用viewを作り、call/resultの対、所有者、parts、定義・件数・raw overridesを検証してHTTPを送る。
4. SDKでsemantic SSEを集約する。`output_item.done` で引数が完成しても、応答全体の終了・整合検査前には実行しない。
5. completed応答の再送情報を保存する。元のツールsnapshotでcall batch全体を検証・保護し、既存の `ToolLoopSupport` を通して実行する。
6. 結果・配信属性・継続参照を既存の保存境界で確定し、次iterationで新snapshotと新しいinputを作る。

`searchTools` は次回要求の公開状態だけを更新する。同一応答中の `[searchTools, 未公開call]` を実行できるようにはしない。名前ベースのグローバルresolver fallbackも有効にしない。

`response.incomplete` は完成した応答としてdispatchしない。`max_output_tokens` は既存の出力上限回復へ渡し、未完成のJSONや引数を実行・保存済み成果にしない。不完全な応答は診断情報として残し、次要求の有効なcall/result履歴には混ぜない。回復要求は、その応答を生成する前の有効なPromptと既存予算から作り、結果のないfunction callを残さない。`response.failed`、terminal eventなしの切断、キャンセルではtool executionを開始しない。SDKが終端応答から `LENGTH` / usageを返す動作をテストし、未完成の応答をSDKが途中で通常完了に見せる場合にもRei側でterminal状態を検証する。

再試行は既存の `StreamTimeoutRetry` / Run予算を使用し、SDKのmaxRetriesは0を維持する。retryは同じ接続先・snapshot・inputを使い、既に結果が見えている応答や成功済みtool操作をfallbackで再実行しない。

## 観測・記録・互換性

`ChatStreamTimeoutInterceptor` は両APIを識別して接続・read inactivity・全体期限の契約を維持する。Responsesの推論deltaも受信活動とするが、既存の抽出全体期限を延長し続けない。

`CapturingChatModel` / `CaptureInterceptor` の記録対象にResponsesを明示追加する。captureは再送情報の正本にしない。通常ログにはAPI形式、endpoint ID、要求ID、toolset版、件数、HTTP status、具体的な互換性エラー、usageを残す。APIキー、暗号化blob、平文推論全文を通常ログに出さない。既存の明示captureには保持・削除・表示制約を適用する。

responseの `phase` は文脈・UI判定の両方で保持する。途中のcommentaryをRun完了とみなさず、推論textを最終回答・音声読み上げへ混ぜない。既存のイベント順序と結果配信属性を維持する。

API変更を含むエラーでは、今回のような汎用 `invalid_state` だけでなく、tool上限、旧snapshot不足、payload欠落、API/再送方式不一致、認証・引数のどれかを利用者が識別できるようにする。自動のモデル変更や引数削除を回復策にしない。

## 段階実装と受入条件

全段階で、小さな失敗fixtureを先に作るRed、必要な変更だけで通すGreen、契約を保って整理するRefactorを繰り返す。実 API の成功だけを根拠にせず、SDKの実HTTP JSONとSSE・実SQLite保存を境界にする。

| 段階 | 最初に失敗させる例 | 受入条件 |
| --- | --- | --- |
| Phase 1: optionsと共通通信 | Responses選択後もChat optionsが送られる、上限名の混入、tool-freeでtool_choiceだけ残る、rootの設定が機能別モデルへ反映されない | 両生成経路とスキル・メモリで `/responses`、`max_output_tokens`、推論設定、store:falseを実JSON検査。既存custom serverは明示変更まで旧API。ツールなし要求は両フィールドなし。再試行0、secret非出力 |
| Phase 2: parts・推論・tool往復 | 平文推論が次要求から消える、暗号化blobの変化、item idとcall_idの取り違え、同応答の未公開call、partial引数のdispatch | encrypted/plaintext双方で、複数itemsの順序、phase、引数、結果、未知itemの扱いが往復で一致。streamと非stream双方を検証。基本32→検索後追加を確認。業務ツールは元snapshot・既存承認を通る |
| Phase 3: 保存・承認・再開・圧縮 | DB往復でparts消失、承認再開で元assistant欠落、成功prefixの再送、圧縮でcall/result分断、異なるendpointへのopaque再送 | プロセス再起動・同Run承認・新Run checkpoint・親子Runを実保存で確認。旧データ互換。モデル応答の参照欠落/上限/保存失敗は副作用前に停止。操作後の保存失敗は既存台帳で照合し再実行しない。完了transactionだけ圧縮 |
| Phase 4: 統合・運用確認 | capture/read timeoutが旧APIだけ、terminalなしで完了、usage二重計上、API形式を変えたfallback | 権限、READ_ONLY、CONVERSATION、キャンセル、配信、停滞、予算、観測、既存旧API経路の回帰成功。OpenAI・DeepSeekで推論ON、toolなし、基本操作、検索追加、結果返却、再開を実接続確認 |

Phase 1・2だけで本番の長時間Runへ有効化しない。Phase 3の保存・再開とPhase 4の回帰が完了してから、利用する接続先を明示して有効化する。実接続試験では送るデータとツール定義を事前に明示し、通常の権限・承認に従う。

実装の完了条件には、Checkstyle・SpotBugs指摘0件、既存アーキテクチャゲート、対象経路の回帰を含める。本設計ではアーキテクチャ予算や既存テストを変更しない。必要になる依存辺は実装時に個別に評価し、生成report全体のコピーでゲートを通さない。

## 調査済み事項と残る確認

確定しているのは、SDKのstateless Responses対応、native optionsの別型、暗号化推論の再送、平文推論のnative再送欠落、限定変換での非streaming再送、現在の本文中心の保存経路である。

実装時にfixtureで確定する事項は、平文streamの全文復元と複数items、payloadサイズ・入力見積もり、旧再開データのAPI世代識別、captureと各機能のtyped options置換である。これらを未検証のまま「OpenAI互換なので動く」と扱わない。

主な残存リスクは、推論payloadの増大、圧縮と参照削除の整合性、提供元ごとの対応外フィールドの黙示無視、SDK更新によるparts変換規則の変化である。DeepSeek公式APIと別サービスの同名モデルは同一の互換性契約とみなさない。

## 外部仕様の根拠

- [Spring AI 2.1.0 Responses対応](https://docs.spring.io/spring-ai/reference/api/chat/openai-responses-chat.html): native model、API選択、設定prefix、stateless動作。詳細動作は同版source JARのクラス・メソッドを上記表で照合した。
- [OpenAI Responses移行ガイド](https://developers.openai.com/api/docs/guides/migrate-to-responses): input/output items、function call/result、文脈の引き継ぎ。
- [OpenAI推論モデル](https://developers.openai.com/api/docs/guides/reasoning): 推論とツール利用、再送する推論情報。
- [DeepSeek Responses互換仕様](https://api-docs.deepseek.com/guides/responses_api/): function、SSE、max_output_tokens、平文reasoning、非対応のサーバー側継続機能。
- [DeepSeekモデル仕様](https://api-docs.deepseek.com/quick_start/pricing/): `deepseek-flash` とV4.1 Flash 1017の対応。

外部仕様の確認日: 2026-11-24。新しいSDK・モデルへ更新する際には互換性fixtureを再実行する。

## 2026-11-24 改訂: Phase 0–6 の実装契約

以下は上記の調査メモ・旧4段階計画に優先する。元の調査は実装済みの証拠ではない。
本依頼ではソースのビルド・検証・PRマージを行う。本番プロセス、外部設定、本番DBを変更しない。

### 基準差分

元の設計基準 `18894acb` はローカル main のみに存在し、取得したリモート main
`cc12001e` の祖先ではなかった。リモートは共通祖先 `e774ec36` 以降、セキュリティ境界の修正、
モデルの旧接続先アンロード廃止、Native Client 改善、Maven Wrapper 3.10.1 更新を含む。
Phase 0 専用 worktree で両履歴を通常マージした。元の worktree と未追跡ファイルは保護する。
動的ツール選択を欠いたリモート版だけを基準に機能を実装し、全公開に戻すことはしない。
既存のアーキテクチャ基準はこの前提コミットの履歴を保持し、Responses追加のために緩和しない。

### 責務と実効接続構成

`LlmModelProvider` を窓口として維持する。Protocol Adapter は native options、入力の送信用 view、
リクエスト整合性、terminal status、usage/error の正規化だけを担当する。
ToolLoop、承認、Goal、Run、Checkpoint、DB、圧縮判断を所有しない。
Spring の ChatModel / ToolCallingChatOptions と重複する汎用フレームワークは作らない。

解決済み接続構成は不変の値とし、endpoint ID、互換性世代、明示API形式、正規化URL、
サーバー内の認証参照、モデル、能力、生成上限、timeout を同じスナップショットにまとめる。
認証の秘密値は参照先だけが持つ。fingerprint は秘密値をハッシュする方式も採らず、
API形式・再送方式・互換性世代・所有者境界を用いる。キーの通常ローテーションと互換性世代を分ける。
組織・プロジェクト・認証主体が変わる場合は互換性世代を明示更新する。
キャッシュは同じ解決済み構成からモデルとoptionsを作る。Run受付時の固定と再開時検証は
既存の受付・継続保存境界に統合し、接続先選択UIは追加しない。

能力はホスト・モデル名から推測しない。必要な再送方式 `encrypted` / `plaintext` / `none`、
ツール上限、reasoning summary、parallel tool calling制御、structured output、画像入力について、
使用する時点で明示構成を検証する。`none` は失敗時fallbackではない。

### native options と安全な有効化

Phase 0 は Chat Completions の動作を保持し、Responses を本番経路に公開しない。
Phase 1–3 の Responses 通信・tool-loop試験はテスト用境界に限定する。
Phase 4 の耐久性・再開・回帰検証、利用先の実接続検証が満たされるまで本番有効化を拒否する。
実接続には認証・送信データ・費用・権限の明示確認が必要。未検証は利用可能と表示しない。

依存実装の再確認: Spring AI 2.1.0、OpenAI SDK core 4.101.0。
同版の `OpenAiResponsesChatModel.buildRequestPrompt` は native options を defaults と再マージしない。
Rei が完全な要求optionsを作る。`ResponsesRequestBuilder` は store:false と暗号化includeを設定するが、
extraBodyを後から追加でき、toolsが空でもtool_choiceを設定するため、Rei側で保護・省略を保証する。
`ResponsesItemMapper` は暗号化payloadのないReasoningPartを除去する。欠損を成功扱いしない。
ストリームはitem完了時にもcallを公開するため、terminal completed検証より前にdispatchしない。
terminalなし切断をSDKの正常Flux完了だけで成功扱いしない。

Responsesではmax_output_tokensのみを使い、Chat固有パラメーターを暗黙変換しない。
ツールなし要求はtools/tool_choiceの双方を省略する。Hosted Toolsは初期対象外。
許可callback・元要求snapshotでcall batch全体を検証し、strict:falseを維持する。
extraBodyの保護フィールド上書き、未対応設定、名前重複、不正schema、件数超過を送信前に拒否する。

### メッセージ保存と再送

`ModelMessageCodec`: version付き許可part schemaと旧schema読み込み、形式整合性。
Javaクラス名を入力にする任意デシリアライズを禁止する。
`ModelMessagePayloadStore`: 追記保存、所有者/hash検証、冪等性、参照整合性、容量制御、GC。
`ResponsesReasoningReplay`: 取得済みデータから送信用viewを作り、API・endpoint・所有者・世代を検証。
DBへ直接アクセスせず、元メッセージを変更しない。opaque payloadを改変・要約・復号しない。
複数itemsの順序、item id/call_id、commentary/final_answerを保持する。

同Runのtool往復、同接続先の承認再開、互換checkpoint再開は必要payloadを保持する。
完了会話を別接続先へ引き継ぐ時はテキスト投影だけを使い、未完了Runの別接続先への継続は拒否する。
必要payloadの欠落は失敗とする。完了済み過去turnの推論再送範囲は提供元仕様で決め、無制限に保持・送信しない。

### 障害境界・保持・容量

| 境界 | 復旧契約 |
| --- | --- |
| 完成応答取得後・payload保存前 | ツール未実行。既存予算による再要求のみ |
| payload保存後・参照確定前 | 孤立payloadを要求ID・起点Run・anchorで照合。参照確定中と区別 |
| 参照確定後・ツール実行前 | 既存台帳で未実行を確認して再開 |
| ツール実行後・結果保存前 | 副作用を照合。不明なら停止し、自動再実行しない |
| 結果保存後・次要求前 | 保存済み結果を再配信し、ツールを再実行しない |

複数DB・JSONLの一括transactionは仮定しない。外部副作用のexactly-onceをDBだけで保証しない。
新payload表はRun・業務状態を管理する第二台帳にしない。
実行中Run、有効履歴、承認、Checkpoint、継続情報の参照があるpayloadを削除しない。
保存中leaseまたは同等の既存ライフサイクル保護で、参照確定とGCの競合を防止する。
独立Schedulerは作らない。メッセージ、Run、領域全体の容量を測定・制限し、超過では切断せず安全停止する。

圧縮はAPI非依存の完了transaction単位とする。未完了call/result、承認待ち、成功prefix、
結果不明の操作を分断しない。protocol itemsとpayload参照を同時に有効範囲から外し、
throughSequenceを整合させる。要約失敗時はカーソルを進めない。
Rolling Summary、Work Context、ツール定義、Skills、結果短縮、停滞指示、System変更の影響は
構造テストと実推論継続性の検証を分けて記録する。

推論は通常ログ・GUI・CLI・TTS・RAG・全文検索・長期記憶に混ぜない。
明示captureにも既存権限・保持・削除を適用する。DB/WAL/backup/削除領域に残存し得るため、
論理削除で物理消去やすべての提供元データ保持停止を保証しない。store:falseは全保持無効化を意味しない。

### フェーズ別ゲート

| Phase | 内容 | 追加検証と有効化境界 |
| --- | --- | --- |
| 0 | 接続先管理・API・能力・Adapter・設計整理 | 既存設定とChat回帰。Responses本番拒否 |
| 1 | Responses基本通信・native options・structured/画像・usage/status | 実HTTP JSON、SSE、timeout/cancel/異常terminal。テスト境界のみ |
| 2 | OpenAI encrypted replay・既存ToolLoop/動的選択 | 複数parts/phase/call_id、元snapshot、部分応答dispatch禁止。テスト境界のみ |
| 3 | codec/store/schema・完全保存/復元・参照/GC | 実SQLite、冪等/hash/所有者/容量、障害注入、再起動、GC競合 |
| 4 | 承認/Checkpoint/Goal/子Run/圧縮/既存各機能 | 親子所有者、成功操作再実行禁止、全関連回帰、本番可否の独立判定 |
| 5 | DeepSeek plaintext replay | 非stream/stream完全復元、複数items、再開、encrypted回帰。SDK制約は明示 |
| 6 | 長時間運用・性能・移行/切り戻し | 全回帰・大容量/繰返圧縮/異常終了。未測定を推測で埋めない |

各PhaseでRed→Green→Refactoringを記録し、Checkstyle/SpotBugs指摘0、既存architecture gate、
関連回帰、build、PR差分レビュー、必須CIと承認確認、通常マージ、専用worktree削除を行う。
前Phaseのマージ前に次Phaseを開始しない。実績はresponses-api-implementation-progress.mdに記録する。

### Phase 0 で利用できる設定

既存設定の省略値は変更しない。機能別 `api` を省略した独立custom serverはChat Completionsを使う。
`api: chat-completions` も明示できる。`api: responses` は安全な永続化・再開の統合前には
利用不可として明示エラーにする。SpringのResponses既定モデルからの誤ったnative options生成、
legacyモデルからResponsesへのfallback、private secretaryでの黙示的なAPI無視も拒否する。

`endpoint-generation` は正の整数で既定1。設定カタログは起動中に書き換えず、モデルと要求optionsを
同じスナップショットから作る。通常のキー更新のみなら世代を変えず再起動する。
認証主体・組織・プロジェクトや再送互換性が変わる時は明示的に世代を更新する。
この設定追加だけで承認・CheckpointのResponses再開が実装済みになるわけではない。

OpenAIの将来の概念設定例のモデル名は[公式モデルページ](https://developers.openai.com/api/docs/models/gpt-5-mini)
で確認した `gpt-5-mini` に修正した。DeepSeekの`deepseek-flash`は公式Responsesガイドの例に基づく。
いずれもReiでの実接続は未検証で、概念設定例をそのまま本番有効化することはできない。

### Phase 0 の明示ツール能力

`rei.llm.max-tools` / `rei.llm.features.<feature>.max-tools` を追加した。
値は正の整数。接続先が実際に受け付ける件数を運用者が明示し、HTTP成功から能力を推定しない。
独立接続先はroot能力を継承せず、明示fallbackは候補の小さい上限を用いる。
動的公開枠`max-active-tools`の1〜128制約と、未指定時の既存送信検証を維持する。
基本32件を保持できない能力ではRun開始を拒否する。
能力はbackend内部のtool contextにのみ伝え、HTTP bodyの追加フィールドとして送らない。

### 実際の依存アーティファクトとの照合

作業用Mavenキャッシュへ2.1.0/4.101.0のsource JARを取得した。
SpringのModel/Options/RequestBuilder/ItemMapper/StreamAssemblerの5ソースは、
前回調査で展開されていたものと改行正規化後に完全一致した。
実JARのprivate bytecodeも確認し、native optionsがdefaultsを置換する分岐を照合した。

SDKのretry回数はクライアント構築時に設定され、runtime request optionsのmaxRetries値だけでは
既存クライアントのretryを変更しない。既存Chat経路はOpenAiSdkConfigurationが構築時0を保証する。
Responses factoryでも構築時0を保証する。Spring builderには公開の同期/非同期SDK client注入点がある。
固定encrypted include等の提供元差を補う必要があれば、その公開点でtyped SDK paramsに限定したAdapterを
検証する。HTTP interceptorによるJSON書き換え、別SSE parser、別エージェントloopは採用しない。

既存`/model`の選択はModelHolderServiceが保持し、要求optionsへのモデル引数として維持する。
接続設定の固定と、受付時に選ぶモデルを混同しない。Runへの統合時は同じ選択済みモデルから
実効構成を確定し、設定だけのdescriptorを要求モデルの代用にしない。

### Phase 1 の通信境界

`ResponsesAdapter` はSpring AIのnative optionsとモデルを使用する。production providerには登録せず、
永続化・再開の受入条件が整うまで既存の明示拒否を維持する。HTTP 200のみでは成功と判定しない。
非stream応答・stream終端の **response metadata** のstatusが明示的にcompletedであることを要求する。
Generation metadataのstatusはSpring側で欠落時completedを補うため、成功判定の根拠にしない。
streamのEOFだけでは成功にせず、キャンセルではSDKのボディを閉じる。

接続先の設定対応は不変な`LlmCapabilities.supportedSettings`で明示する。温度、top-p、推論effort、
summary、parallel制御、structured output、画像入力をモデル名から推定しない。構造化出力はnative
`text.format`、画像はnative`input_image`で送信する。能力集合は防御コピーしたenum順の集合とし、
fingerprintの順序を安定させる。この段階では外部YAMLによるResponses本番利用設定は公開しない。

出力上限の欠落・増加、モデル・接続設定・SDK retryのruntime変更、保護フィールドのextraBody上書きを
送信前に拒否する。native runtime optionsはdefaultsを置換するという依存実装の契約を維持し、
部分optionsを黙示マージしない。ツールなし要求はtoolsとtool_choiceを両方省略する。
受け取ったcallback定義の名前・重複・件数・object Schemaを検証し、業務callbackは通信層で実行しない。
Hosted Tools、server truncation、hosted maxToolCalls、function strict:trueは初期契約の範囲外として拒否する。

Spring AI 2.1.0の内部`MessageAggregator`はstreamの例外をログ出力する。Adapterの下流で例外を
置換するだけでは、提供元メッセージに含まれ得る秘密情報・推論のログ出力を防げないことをfixtureで確認した。
`ResponsesSdkBoundary`は公開client注入点でSDKの型付きstreamだけを包み、error/failedイベントと
異常完了を安全な分類へ置き換えてからSpringのassemblerへ渡す。HTTP/SSE解析と資源解放はSDKが担当する。
JSONのinterceptor書き換え、独自SSE parser、ログの無効化は採用しない。SDK更新時にはこの境界の
実SSE・ログ捕捉・キャンセル契約テストを必ず再実行する。

SDK retryは同期・非同期clientの構築時に0とする。既存のread inactivity制御を`/responses`にも適用し、
読み取りが続く長い応答と無通信timeoutを区別する。認証・rate limit・server/transient・provider・timeoutの
エラー分類には提供元body・メッセージ・元例外causeを含めない。通常のusageとnative usageの
cached/reasoning token数は保持する。incomplete/failedでも、提供されたusageの数値だけを安全な例外へ保持する。
未提供のusageは未取得として扱い、推測で埋めない。既存予算・再開との統合は後続phaseの課題として残す。

raw extraBody拡張は現時点で公開しない。保護フィールドを個別に診断したうえで、その他の拡張も
型付きの対応契約が追加されるまでは拒否する。SDKが受理できる値を接続先の対応済み機能とみなさない。
名前付きtool choiceは要求内で公開したfunctionに限定し、hosted tool指定や不正JSONは値を表示せず拒否する。
deployment alias/Foundry設定は現在のresolved model契約の範囲外として拒否する。

SDK retry:0だけでは物理的な再送を止められないことも、実loopbackで確認した。
同梱Spring HTTP clientはOkHttpのconnection recoveryを有効にしており、408/503 + Retry-After:0の
同期・非同期要求はSDK retry:0でも2回届いた。Responses所有のinterceptorで本文をnon-replayableと宣言し、
4ケースすべて1回だけ届くことと、受信したJSONが元のnative SDK JSONと等しいことを検証した。
本文のJSONやopaque payloadは書き換えない。共有customizerの後に所有するrequest/timeout方針を適用し、
共有の5秒read timeoutが解決済み150msのtimeoutを上書きしないことも実clientで検証する。
この方針はResponses factoryのみに適用し、既存Chat Completions clientの再送動作は変更しない。

## Phase 2 の再送・実行境界

`ResponsesReasoningReplay` は DB にアクセスせず、native MessagePart から送信用 view を検証する。
ENCRYPTED 要求にはバックエンド tool context の明示 owner を必須とし、応答の assistant metadata に
owner と解決済み endpoint fingerprint を結び付ける。これらを HTTP の input に混入させない。
再送時は owner、API、接続先、モデル、互換性世代、再送方式を照合し、不明な出所を現在の接続先由来と
推定しない。認証キーだけの更新は同じ fingerprint を維持する。実 Run の所有者解決・永続化との接続は
Phase 3・4 で実装するため、現時点の factory は引き続きテスト専用とする。

複数の暗号化 payload の文字列・bytes、reasoning item id、function item id と call_id、text phase、
パーツ順序を native HTTP JSON まで検証する。payload 欠損、別 provider/kind、所有者・接続先の不一致、
partial/unknown part は HTTP 送信前に拒否する。none は欠損時の fallback として使用しない。

同期応答は明示 terminal status の検証後、完全な parts を検証してから呼び出し元へ返す。
stream は raw terminal status を記録し、既存 Spring MessageAggregator で集約した parts の検証が
終わるまで完了を下流へ伝えない。Spring の集約結果は独自 response metadata の STATUS を保持しないため、
集約後に generation の既定値を terminal 証拠とみなさない。途中の表示 chunk は維持し、ツール実行は既存の
StagnationChatModel / BoundedToolLoop / ToolLoopSupport が正常完了した要求に対してだけ行う。
重複 item/call id、未完了 function、壊れた引数 JSON、Hosted Tool は完了扱いにしない。

既存 ToolLoopSupport の不要な toolCalls setter は item 属性を失うため使用を避ける。ファイル継続引数を
補う場合もパーツの位置・payload・属性を保持する。引数修正を扱う同期中の実行経路では、実行 view と
モデルが返した元 assistant を区別し、次要求には元の ordered parts を保持する。
承認待ち保存・プロセス再起動の完全な parts 参照は Phase 3・4 の永続化契約で扱う。

native incomplete/max_output_tokens は Spring の LENGTH 正規化を使って既存の出力上限回復へ渡す。
同期要求では部分的な reasoning/function を結果から除き、stream は既存ループが LENGTH を先に判定する。
共有 ToolLoopSupport も truncated batch の実行を拒否する。他の incomplete、failed、EOF は失敗であり、
reasoning を落として正常な要求として再試行しない。返された数値 usage と未知 usage を区別する。

動的公開は基本31件＋searchTools、検索の効果は次要求からという既存スナップショットを維持する。
同じ応答の未公開 sibling は検索自体より前にバッチ全体を拒否する。既存の権限・承認 guard も通す。
外部 API を呼ばない native JSON/SSE fixture と既存ループの結合試験で検証し、別 agent loop は追加しない。

集約された usage には追加の依存制約がある。Spring MessageAggregator は prompt/completion/total の
3数値から Usage を再構築し、native cached/reasoning 内訳を破棄する。独自集約器を作らず、raw terminal の
native usage はそのまま保持し、さらに正規化した5数値だけを assistant の backend metadata に運ぶ。
同じ集約器が保持する metadata から内訳を復元する。本文・opaque payload・提供元の任意フィールドを
usage に保持しない。標準の集約済み Usage.getNativeUsage() が元のSDK値を返すとは扱わず、Rei側の
正規化値を検証する。要求 input にこの計測metadataを混ぜない。数値の未提供は null のまま区別する。
