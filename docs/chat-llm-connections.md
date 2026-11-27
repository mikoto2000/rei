# 通常チャットの接続先・モデルを切り替える

OpenAI API、ChatGPTサブスク、ローカルLLMを同じ接続プロファイルとRun固定の仕組みで扱う。通常チャットのツール選択・実行・承認・履歴・CheckpointはReiが管理する。

接続先の選択は**接続したReiサーバーのプロセス全体で共有**する。CLIとGUIの両方から操作でき、新しく受け付けるRunへ適用する。実行中、承認待ち、Checkpointから再開するRunは受付時の接続先・モデル・アカウント登録を維持する。GUIを開き直すとサーバーの現在値を取得し、Reiサーバーを再起動すると設定ファイルの`active`へ戻る。

## 初期設定

外部の`application.yaml`の保存先は`/config path`で確認できる。既存設定へ次の構成を組み合わせ、URLとモデルIDを実際に確認した値へ置き換える。

```yaml
rei:
  chatgpt-plan:
    enabled: true
    # 通常チャットだけならSubAgentのロール許可は不要。
    allowed-roles: []
  llm:
    features:
      chat:
        active: local
        endpoints:
          local:
            provider: openai-compatible
            api: responses
            authentication: none
            base-url: http://127.0.0.1:8000/v1
            model: ${REI_LOCAL_CHAT_MODEL:replace-with-confirmed-local-model}
            usage-path: local-llm
            reasoning-replay: none
            server-capabilities:
              responses: unknown
              streaming: unknown
              function-calling: unknown
            model-capabilities:
              function-calling: unknown
          openai:
            provider: openai-api
            api: responses
            authentication: api-key
            api-key: ${REI_NAMED_OPENAI_API_KEY:}
            base-url: https://api.openai.com/v1
            model: ${REI_API_CHAT_MODEL:replace-with-confirmed-api-model}
            usage-path: api-billing
            reasoning-replay: none
            server-capabilities:
              responses: unknown
              streaming: unknown
              function-calling: unknown
            model-capabilities:
              function-calling: unknown
          subscription:
            provider: chatgpt-plan
            api: responses
            authentication: chatgpt-oauth
            account-ref: personal
            model: ${REI_PLAN_CHAT_MODEL:replace-with-account-model-slug}
            usage-path: chatgpt-plan
            reasoning-replay: encrypted
            server-capabilities:
              responses: unknown
              streaming: unknown
              function-calling: unknown
              tool-namespace: unknown
              reasoning-replay: unknown
            model-capabilities:
              function-calling: unknown
              reasoning-replay: unknown
```

これは確認項目を示す設定例で、記載したサーバーやモデルを動作確認済みにするものではない。`unknown`のままでは必要な能力を使えない。運用者が確認したサーバー・モデルの項目だけを`supported`へ変更する。非対応は`unsupported`と明示できる。`/models`への掲載だけではFunction Callingや推論再送の対応確認にはならない。

OpenAI APIを登録する場合は`REI_NAMED_OPENAI_API_KEY`を設定する。未設定の空キーは起動時に拒否されるため、使わない`openai`ブロックは削除する。`authentication: none`には`api-key`を記載しない。rootや他の接続先のキー、環境変数の暗黙継承、dummy keyの補完は行わない。`subscription`に任意URLやAPIキーを指定することもできない。

ローカルの認証なし接続とChatGPTの登録だけを使う構成には、OpenAI APIキーは不要。Plan未ログインでも起動して設定を確認でき、推論受付時にはログイン・能力・登録の条件を検査する。Rei Web APIの`REI_API_KEY`はクライアントからReiへの認証用で、モデルサービスのキーとは別である。

Chat Completionsを利用する接続先では`api: chat-completions`とし、`server-capabilities.chat-completions`と`streaming`を確認する。Function Callingにはサーバーとモデル双方の確認が必要。API、Provider、認証方式は独立した設定であり、モデル名やホスト名から推測しない。

初回にモデルIDが不明な場合は、まず`chatgpt-plan.enabled`だけを有効にした従来構成で認証・モデル一覧の確認を行い、その値で名前付き接続先を登録して再起動する。**登録内容の変更には再起動が必要だが、登録済み接続先の選択には不要**。

## 利用開始

1. 上の構成で接続先を登録し、モデルID・対応宣言・必要なキーを設定してReiを起動する。
2. ChatGPTを使う場合は`/chatgpt login --account personal`を実行し、本人がブラウザでアカウント・Workspace・同意内容を確認する。保護ストアなどの前提は[既存の認証ガイド](chatgpt-plan-subagents.md)を参照する。
3. `/chatgpt status`、`/chatgpt models --account personal`で登録と利用可能なモデルslugを確認する。通常チャットで選択中の接続先のモデル一覧は`/models`、選択は`/model MODEL`を使う。
4. `/llm chat list`と`/llm chat show`で現在値、認証状態、利用経路の申告、能力、非適用設定、利用不可理由を確認する。
5. `/llm chat use subscription`でChatGPTサブスクへ切り替える。引数なしの`/llm chat use`では対話選択できる。
6. 新しい通常チャットを開始する。必要な送信承認では宛先・アカウント・実際の入力とツール定義を確認する。接続先を選ぶ操作自体は、推論や履歴の外部送信への承認ではない。
7. `/llm chat use local`でローカル接続へ戻し、`/llm chat show`で現在値を確認する。選択を戻しても、既に受け付けたRunの接続先は変わらない。
8. デスクトップGUIでは会話画面の「LLM接続先」で同じ操作を行う。「モデル一覧を取得」を押してから「LLMモデル」を選ぶ。矢印キーとEnterでも接続先を選択できる。

GUIの対象はその会話が接続しているReiサーバー。設定画面で別に選んだサーバーには送らない。変更中は通常送信を止め、CLIなどによる新しいモデル変更と矛盾する古い応答は破棄する。「接続先を更新」で現在値を取得し直せる。古いReiサーバーにLLM選択APIがない場合は案内を表示し、既存の通常チャットを維持する。

## 能力・生成設定・モデルごとの宣言

実効能力はReiのアダプター制約、サーバー宣言、モデル宣言の積である。どれかが非対応なら非対応、どれかが未確認なら未確認。宣言を追加しても経路の禁止を解除できない。

`model-capabilities`はプロファイルの設定モデルの宣言。別モデルへ切り替えるとモデル固有の確認を引き継がない。複数モデルを確認済みなら、同じプロファイルへ次のように登録する。

```yaml
model-capabilities-by-name:
  confirmed-second-model:
    function-calling: supported
    reasoning-replay: supported
```

モデル名は正確に一致させる。ドットやスラッシュを含む名前をproperties形式で指定する場合は`model-capabilities-by-name.[exact/model.id].function-calling=supported`のように括弧で囲む。ChatGPTのencrypted経路では、追加モデルの`reasoning-replay`も確認しないとRunを受け付けない。既に受け付けたRunには宣言の変更を適用しない。

`generation`には対応確認済みの設定だけを指定する。例として`reasoning-effort`にはサーバー・モデル双方の`reasoning-effort: supported`が必要。Structured OutputのJSON Schemaは`response-format`内の`json-schema`へJSON文字列として記載する。

```yaml
generation:
  response-format:
    type: json_schema
    strict: true
    json-schema: |
      {"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}
```

この例には`structured-output`の対応確認が必要。未知・非対応・APIと不整合な明示値は、削除して再送する代わりに拒否する。`max-output-tokens`、温度、各設定の非適用理由はCLI/GUIで確認できる。明示したOpenAI APIのChat Completionsでは`max_completion_tokens`を使用し、互換サーバーの既存`max_tokens`と区別する。

## ChatGPTサブスク経路と利用枠

通常チャットは公開Responses APIを使い、`store=false`、`stream=true`、必要な履歴を含む明示入力、Reiの名前空間付き関数を使用する。Planで禁止する出力上限・温度・top-pなどは送らない。HTTPの`previous_response_id`を使わず、既存のRei履歴を管理する。根拠は[公式の推論仕様](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)と[プレビュー制約](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)。

利用経路の表示は運用者の申告で、送信権限や残量の確認を意味しない。推測した残量・解除時刻による再開や、別アカウント・API課金・ローカルへの自動フォールバックはしない。利用枠による停止時はサービス側の利用状況を確認し、必要なら`/chatgpt resume --account personal --confirm-usage`でこのアプリの受付停止を明示的に解除する。このコマンドは推論を送らず、実際の残量を検証するものでもない。

同じアカウント登録の通常チャット・既存の直接推論・app-serverは要求枠を共有する。ツール実行や承認待ちでは枠を保持しない。通常チャットの変更によって要約、Work Context、Scheduler、Goalなどの補助推論や既存SubAgentの接続先を暗黙に変更しない。

## 履歴・承認・再開

接続先を変えた新しいRunでは、確定した会話と完了した操作の結果を共通履歴へ投影する。元の暗号化・平文payloadや未完了ツール処理を別接続先へ移植せず、元データを上書きしない。互換性のない媒体は黙って消さず拒否する。

承認は宛先・登録・所有者・実際の内容と結び付く。追加指示や送信内容の変更には新しい承認が必要になる。管理者のDENYやモードの制限を、推論承認で上書きすることはできない。承認待ちとCheckpointの再開では保存済みの接続を使い、設定削除・送信先変更・登録し直しなどで束縛が一致しない場合は停止する。

CONVERSATIONではツールを実行しない。READ_ONLYでは書き込みを許可せず、EXCLUSIVEでも既存のツール権限と承認を適用する。部分SSE、未完成の引数、usage不明、異常終了を成功扱いにせず、成功済みの操作を自動で繰り返さない。

## 動作確認と未確認事項

実サービスのバージョン・モデル・テンプレート別の確認状況は[互換性マトリクス](chat-llm-compatibility.md)に記載する。本実装作業での通信試験は所有するloopback/pre-DNS fixtureで、実OpenAI API・実ChatGPTアカウント・実ローカルモデルへの推論は未実施。fixtureの成功を実サービスでの動作確認済みとは扱わない。

実接続試験は、利用者が許可した接続先・モデル・小さな検証用Projectで行う。まずテキストのCONVERSATION、次に確認済みのFunction CallingをREAD_ONLYで1回、接続切替、承認待ちとCheckpointの再開を順に確認する。宛先・利用経路・認証ヘッダーの有無・正常終端・usage・ツールの重複実行なしを記録する。実資格情報や送受信本文を通常ログや検証記録へ保存しない。

未確認のサービス・モデルの組合せ、実機GUIを介した実サービス接続、サービス変更後の互換性は残る確認項目。互換性を確認してから宣言を更新し、必要な場合は既存Runを終了して新しいRunを開始する。
