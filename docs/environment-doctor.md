# 環境ドクター

現在のJVM、実効設定、音声モデルなどのファイル状態を、故障と未確認を分けて確認します。
REPLで `/doctor` または `/doctor --details` を実行してください。
既定はPassiveです。Active診断は `--check` で項目を明示し、既存Runと権限経路を使います。

Passive診断はHTTP通信、モデル推論、外部プロセス起動、録音、JNIロード、ダウンロード、修復を行いません。
RunやSessionを新規作成せず、CLIから設定・ファイルのメタデータを読むだけです。
起動していないアプリや、Spring設定のバインド自体が失敗する状態を起動前に診断する機能ではありません。

## 対象と判定

- Javaのfeature version、OSファミリー。
- 既存OpenAI互換接続とchat用上書き、APIキー・モデル選択の設定有無。現在のモデル選択は既存ModelHolderServiceから取得します。
- 既存Codex / Claudeの有効状態とコマンド設定の有無。実行ファイル・認証・起動は未確認です。
- 既存VoicePropertiesのモデル配置と固定VoiceModelManifestのファイルサイズ。管理済み配置または手動配置を確認します。
- マイクの明示選択の有無。デバイス列挙・取得・録音は行いません。
- embedding、初学者レビュー、computer-useの有効状態。
- 管理者が追加指定した必須ファイルの存在（最大16件）。

音声モデルが任意・未配置ならNOT_CONFIGURED、auto-startが有効で欠落していればERRORです。
一部だけ配置されている任意モデルはWARNINGです。ファイルの存在とサイズが一致しても、ハッシュ・モデルロード・推論は未確認です。
設定済みのLLM接続、認証、モデル選択、外部CLIはUNVERIFIEDです。リモートGPUの存在や利用状況は推測しません。

| 状態 | 意味 |
|---|---|
| OK | 記載した静的観測（Java要件、ファイルの存在・サイズなど）が成立 |
| WARNING | 設定・配置に注意点、または診断上限あり |
| ERROR | 必須ファイル欠落、静的設定不正、当該診断の失敗 |
| NOT_CONFIGURED | 設定・任意ファイルがない |
| NOT_APPLICABLE | 任意機能を無効化している |
| UNVERIFIED | 設定はあるが動作を試していない、または未検査範囲がある |
| SKIPPED | 診断が無効、またはネットワーク共有パスのため読み取りを省略 |

各結果にID、観測時刻、診断方法、根拠、原因候補、次の対処を表示します。
一部の設定やファイル診断が失敗しても他の結果を返します。OKはアプリ全体の正常動作を意味しません。

## 設定・上書き元・秘密情報

`rei.doctor.enabled` は既定true。falseでCLI登録を外し、診断サービスもSKIPPEDを返します。
追加の必須ファイルは `rei.doctor.required-files` にカンマ区切りで指定できます。

```yaml
rei:
  doctor:
    enabled: true
    active-timeout: 5s # 100ms以上30s以下、Active各項目の上限
    required-files: "C:/Rei/local-model.bin,C:/Rei/local-settings.json"
```

設定を自動修正しません。新しい接続先や認証管理は追加せず、既存設定を確認します。
`--details` は設定キー、優先順位のsource番号、system properties / system environment / application configなどの種別を表示します。
番号は現在のSpring PropertySourceの順序で、最初に値を持つ設定元を示します。
値、APIキー、トークン、認証付きURL、任意の設定元名、コマンド引数、モデル名、ファイルパスや本文は表示しません。
設定バインド後のCLI変更は現在のProperties/ModelHolderを確認しますが、その変更操作自体の履歴は表示しません。
例外メッセージ・スタックもレポートに出しません。

ファイル検査はローカルメタデータ用です。UNC / `//host/share` は読み取り前にSKIPPEDとします。
任意のマウント済みドライブのリモート性を判定する機能はないため、追加ファイルにはローカル配置を指定してください。
ハッシュ検証や巨大モデル読み取り、永続DBの開閉は行いません。

## Active診断

```text
/doctor --check connectivity
/doctor --check inference
/doctor --check codex --check claude
/doctor --check microphone
```

CLIは予定内容を先に表示し、既存ShellConversationServiceのRunキューへ送ります。
設定値を固定したハッシュを承認入力に使い、URL・キー・コマンド・デバイス名を承認ログに保存しません。
Run内でTOOL_PLANNEDを発行してから既存ToolPermissionGuardを通し、許可後だけ実行します。
既定のREAD自動承認ではActive診断は承認待ちでSKIPPEDです。既存 `/approval show` と明示承認後の再実行を使ってください。
Conversation ModeはActive診断を拒否し、Read Only ModeではCLI・マイク取得を拒否します。
音声入力の副作用は既存の一回限りの承認が必要です。コマンド指定だけで権限を拡張しません。

| check | 方法と観測範囲 | 権限 |
|---|---|---|
| connectivity | 既存エンドポイントの `/v1/models` へ認証なしGET。リダイレクト・応答本文なし。HTTP応答、接続失敗、タイムアウトを区別 | NETWORK_READ |
| inference | 既存モデル経路・選択・fallbackで固定の短い入力を一回推論。ツールなし、最大16出力トークン、既存Run予算 | NETWORK_READ |
| codex / claude | 既存管理者設定コマンドの `--version` のみ。所有プロセスと出力サイズ・時間を制限 | EXECUTE |
| microphone | 明示した既存device-idを取得して閉じる。start/readや音声ストリームを呼ばない | EXECUTE + EXTERNAL_SIDE_EFFECT |

HTTP応答は認証・推論・モデル存在の証明ではありません。401等も接続の観測としてWARNINGです。
userinfo・query・fragmentのあるURLは送信前に拒否します。リモートサーバーのGPUや内部ロード状態を推測しません。
推論OKは既存fallbackを含むモデル経路で空でない応答を観測した意味です。一次接続先の成功を断定しません。
推論本文とCLI stdout/stderrはレポート・診断イベントに出しません。モデルの既存イベント/captureの設定は引き続き既存経路のものです。
CLI OKはversionプロセスのexit=0を示すだけで、認証やnative agent推論は未確認です。
マイクOKは取得のみで、録音信号や音質は未確認です。ドライバーが割り込みに応じない場合も待ち時間は上限で戻り、
閉鎖を要求します。そのネイティブ取得が戻るまではBUSYとして追加取得を拒否し、無制限にworkerを増やしません。
起動・ダウンロード・認証・修復・任意コマンド・音声推論の自動実行はMVP外です。
失敗や承認待ちは当該項目の結果にし、他の指定項目を続けます。取消は残りを実行せず停止します。
`--details` の設定元表示はPassiveで使用します。Active結果は常に方法・根拠・観測範囲を表示します。

## トラブルシューティング・検証

NOT_CONFIGUREDは任意機能の故障ではありません。必要な場合だけ既存機能の設定手順を使ってください。
UNVERIFIEDは接続不能・モデル不存在の断定ではありません。別途、明示的に承認した方法で動作確認してください。
必須ファイルのERRORは設定した一覧の同じ番号を手元で確認してください。自動修復やサービス再起動は実施しません。
診断に異常が表示されても他の結果を確認できるため、個々のID・根拠・対処を読んでください。

単体テストは `./mvnw -Dtest=Doctor*Test,RootCommandImageTest test`。
Spring設定バインドの統合テストを含める場合は `./mvnw -Pfull -Dtest=Doctor*Test,RootCommandImageTest test`。
小さな固定manifest、固定時計、偽設定を使い、秘密情報非表示と受動性を検証します。
Activeテストでは偽HTTPサーバー、偽モデル、偽プロセス、偽Java Sound/取得処理で権限・予算・取消・期限・秘密情報非表示を検証します。
実際のLLMサーバー・CLI・物理マイクの動作や実モデル品質は通常CIでは未確認です。必要な項目のみ利用者が明示して実行してください。
HTTP統合とRun統合を含む関連回帰: `./mvnw -Pfull -Dtest=Doctor*Test,DoctorMicrophoneAcquisitionTest,BeginnerReviewRunIntegrationTest,RootCommandImageTest test`。
