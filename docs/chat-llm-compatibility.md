# 通常チャット接続の互換性と検証条件

この表の「fixture」は、所有するHTTPサーバーまたはDNS前のinterceptorへ実際のSDK要求を送り、JSON・ヘッダー・終端・保存再開を検証したことを示す。実サービスのモデル動作や提供条件を確認した意味ではない。

## 接続経路

| Provider / API | 認証 | Reiの経路 | 実サービス・版・モデル・テンプレート |
|---|---|---|---|
| openai-compatible / Chat Completions | none / api-key | 共通Run固定と型付きSDK、fixtureで確認 | 未確認。サーバー製品・版・起動引数・モデル・chat templateを運用者が記録する |
| openai-compatible / Responses | none / api-key | 同じRun固定とResponsesアダプター、fixtureで確認 | 未確認。ResponsesというAPI名だけでは互換と判定しない |
| openai-api / Chat Completions | api-key | 公式送信先制約と`max_completion_tokens`、fixtureで確認 | 実APIへの推論未実施。モデルごとの対応は未確認 |
| openai-api / Responses | api-key | 公式送信先制約と名前付きResponses policy、fixtureで確認 | 実APIへの推論未実施。モデルごとの対応は未確認 |
| chatgpt-plan / Responses | chatgpt-oauth | 固定公開送信先、アカウント登録固定、名前空間付き関数、fixtureで確認 | 実アカウントへの推論未実施。アカウント・Workspace・モデル別の利用可否は未確認 |
| chatgpt-plan / Chat Completions | 任意 | 非対応。設定段階で拒否 | 対象外 |

試験に使うSpring AIは2.1.0、OpenAI Java SDKは4.101.0。これはReiがビルドしたライブラリの版であり、サービス側の版ではない。ローカルサーバー製品の実接続試験はしていないため、Ollama・vLLM・llama.cppなどの特定製品や版を対応確認済みとは記載しない。

## 機能と制約

| 項目 | 共通の判定とfixtureでの確認 | 残る実接続確認 |
|---|---|---|
| 起動 | 隔離JVMでルートOpenAIキー空・Plan未ログイン、名前付きローカル/Planの両初期選択で実アプリを起動。モデルHTTP要求0件 | 利用者のOS・設定・既存データを使った起動 |
| 選択・モデル変更 | CLI/GUIが同じ選択サービスを使い、次のRunだけに適用。モデル変更は接続先を照合してCAS更新 | 実サーバーと実機GUIを組み合わせた操作 |
| 認証なし | キーの取得なし、Authorization/API-key/Cookieなし | 対象ローカルサーバーが実際に認証不要であること |
| APIキー | 自分のプロファイルのキーだけを直前取得。送信先・世代・APIの束縛を再確認 | 実キーの有効性・組織・権限 |
| OAuth | 既存登録・refresh・保護ストアを再利用し、登録IDと要求枠を通常チャット/直接推論/app-serverで共有 | 実ログイン、アカウントの利用条件、利用枠 |
| Function Calling | UNKNOWN/UNSUPPORTEDをCC/Responses共通でHTTP・資格情報参照前に拒否。API表現とサーバー・モデル宣言を分離。完成したcall/result、動的ツール公開、モード、承認を検証 | 実モデルのschema解釈、引数生成、namespace対応 |
| Reasoning再送 | Responsesのencrypted/plaintext/noneを保存・復旧試験。異なる接続先へopaque payloadを移植しない | モデル・サーバー固有形式と必須再送条件 |
| Chat Completionsのreasoning | native plaintextのcodecと明示要求を試験。設定経路の`REASONING_REPLAY`は非対応を維持 | native wire試験だけで設定経路を有効化しない |
| 画像 | MIME、サイズ、data URI/URLと能力を検証。HTTPで型付き入力を確認 | 実モデルのVision性能と対応形式 |
| ファイル | Responsesの入力と、Chat Completionsのinline PDFに制限。別形式を普通のテキストへ黙って変換しない | 実サーバー・モデルのファイル対応 |
| 生成設定 | 実SDKのCC/ResponsesでJSON Schemaの内容とstrictの真偽を照合。対応宣言・型・範囲・APIを照合。明示した非対応値を拒否し、既定値の非適用理由を公開 | モデルの各設定の実効性 |
| SSE/失敗/取消 | 正常終端、部分引数、usage欠落、受信上限、異常終了、キャンセル、暗黙再送なしを検証 | 実サービスの通信切断・制限応答 |
| 承認・Checkpoint | 内容・所有者・宛先を固定し、別プロセスで再開。成功済みツールの重複実行なしを検証 | 実環境の再起動と登録更新時の運用 |
| 補助推論 | 要約/Work Context/Planner/Memory等、Scheduler/Goalが通常チャットの選択を暗黙継承しない | 運用設定が意図した静的接続を指すこと |

モデル一覧の取得はメタデータ照会で、推論成功や機能互換性の試験ではない。各能力の`UNKNOWN`を`SUPPORTED`として扱わず、別モデルの宣言も引き継がない。経路やサーバーが`UNSUPPORTED`なら、モデルの宣言で上書きできない。

## 旧設定・旧policyとの関係

名前付き接続先は共通基盤を使う。従来のflat Chat Completionsと、既存の閉じた`ResponsesProductionPolicy`は維持する。旧policyで固定されたOpenAI向け上限やeffortを、ローカルResponsesへ一般化して押し付けない。

既存SubAgentとCodex app-serverはそれぞれの明示設定を使う。通常チャットのエージェントループをapp-serverへ移す機能ではない。GUIの追加は既存のMCP・Tool実行・Project/Session所有権を変更しない。

## 実接続試験の記録様式

利用者が実接続を許可したときだけ、検証用Projectで[利用ガイド](chat-llm-connections.md#動作確認と未確認事項)の順に実行し、以下を記録する。

| 記録項目 | 記載する内容 |
|---|---|
| 接続と版 | Provider/API/認証方式、Rei commit、SDK版、サーバー製品・版・起動引数 |
| モデル | 実際のID、量子化・templateなどのローカル条件。アカウントは登録ラベルのみ |
| 宣言 | 確認したserver/model capabilityと根拠。未試験の項目はUNKNOWN |
| 送信と結果 | テキスト/ツール/画像/ファイル/生成設定、正常終端とusageの有無、モードと承認結果 |
| 切替と復旧 | 既存Runの固定、別接続先への新規Run、承認・Checkpoint・再起動後の再開、重複操作なし |
| 秘密の扱い | token、キー、cookie、保護本文、生のproviderエラーを記録しない |

サービス側の変更や宣言した条件の変更後は、以前の結果を流用せず該当項目を再確認する。未確認の組合せへの自動フォールバック、unsupported項目の削除再送、根拠のない利用枠の復帰は行わない。
