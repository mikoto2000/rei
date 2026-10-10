# LLM Request Capture (MVP v1.0)

## 目的と範囲

`voice-correction` の補正リクエストは Capture 対象外です。音声の原文・候補の扱いは [音声LLM補正](voice-llm-correction.md) を参照してください。

`/llm capture next` を明示的に実行した場合だけ、同じCLIクライアント・会話の次のキーボード送信に対応するRoot Runを記録します。起動時は必ずOFFです。Promptプレビューではなく、JSONシリアライズと既知のShowUI書換えが完了した後のHTTP本文バイト列をJVMメモリへ保存します。

記録はサーバー受信成功の証明ではありません。HTTPレスポンスのステータス、LLM処理の成功、サーバー内部のchat templateやtokenizationは別の情報です。運用サーバーの受信本文を照合する機能はありません。

対応する現行経路は、`LlmModelProvider`のCHATモデル（Spring AI自動設定のOpenAIモデル、およびCHATの明示的接続設定）を使用する通常CLIチャットです。初回、履歴付き、ContextAssemblerによる圧縮後、Tool定義、StagnationChatModelのツール結果・追加指示・出力制限後の反復、SDK再試行、streamingを同じHTTP取得位置で観測します。

独立した子Run、WEB入力、音声入力、Embedding/Reranker、用途別モデル、背景Reflection/Memory/Activity、外部Codex/Claude Code自身のHTTP、Web/Desktop UIは予約を消費せず対象外です。子Runに親のThreadLocalが残っていても、本文に付随するPromptの明示RunとStoreに登録したRoot Runの完全一致で判定します。用途別モデルのHTTPへ範囲を広げません。

## 操作

| コマンド | 動作 |
| --- | --- |
| `/llm capture next` | 現在のCLIクライアント・会話に予約（会話が無ければ作成） |
| `/llm capture status` | この会話の予約、進行記録、容量・固定上限 |
| `/llm capture off` | この会話の未消費予約だけを取消 |
| `/llm capture list` | 保存されたRunのメタデータ一覧 |
| `/llm capture list <run-id>` | Run内のHTTP試行一覧 |
| `/llm capture show <attempt-id>` | メタデータ、HTTP本文由来モデル名、マスキング済みJSON |
| `/llm capture raw <attempt-id>` | 明示確認後、UTF-8復号と端末制御文字エスケープを施して表示 |
| `/llm capture export <attempt-id> <path>` | 明示確認後、完全取得した原本バイト列を新規ファイルへ保存 |
| `/llm capture delete <run-id>` | 指定Runの本文・メタデータを削除 |
| `/llm capture clear` | 全記録と未消費予約を削除 |

例:

```text
/llm capture next
このプロジェクトの構造を説明して
/llm capture status
/llm capture list
/llm capture list <run-id>
/llm capture show <attempt-id>
/llm capture export <attempt-id> "C:\Users\me\Documents\request body.json"
/llm capture delete <run-id>
```

管理コマンドは予約を消費しません。他のクライアント・会話の送信も予約を消費しません。受付時点でSubmission IDとRoot Run IDへ原子的に結び付けます。初回enqueue失敗・遅延executor拒否はSTART_FAILED、キュー取消はCANCELLEDとして終了します。進行中は新規予約を受け付けません。`off`は進行中の記録を止めません。`delete`/`clear`後はそのRunへ新しい本文を保存しません。

完全IDのほか8文字以上の一意なprefixを指定できます。RunとAttemptは別に検索し、複数一致・未知・削除済みIDを拒否します。CLIの既存パーサーはWindowsのbackslashを文字通り保持します。空白のあるパスを引用符で囲んでください。

## 原本と表示

原本はHTTP本文のbyte[]です。JSONオブジェクトへの変換や再シリアライズは保存・exportに使用しません。本文サイズとSHA-256はこのbyte[]から算出します。モデル名・生成設定は`show`に表示した実HTTP JSONから確認してください。JSONが解析できなければ不明として扱い、設定から推測しません。

`show`は別に取得した表示用コピーで加工します。API key、password、secret、認証token、Cookie、認証情報付きURL、data URI、Base64、画像/音声のdataをマスキングします。**マスキングの完全性は保証しません。** 自由文内の未知の秘密情報などは残る可能性があります。非JSONや表示上限を超えた構造は生本文へfallbackせず、表示を省略します。

`raw`と`export`の確認は既定Noです。JLineで`y`または`yes`を入力した場合だけ実行します。対話入力が利用できない場合やEOF/割込みの場合は拒否します。`raw`は制御文字・Unicode書式制御をエスケープするため、完全なバイト表現ではありません。バイト単位の照合にはexportを使用してください。

ExportはCREATE_NEWとNOFOLLOW_LINKSを使用し、既存ファイルを上書きしません。親ディレクトリは既存である必要があります。`..`、symbolic link親、実パスと異なるalias親を拒否します。OSがSecureDirectoryStreamを提供しないWindowsでは、他プロセスによる親ディレクトリ差替え競合の完全な排除は保証しません。信頼する自分の出力ディレクトリを使ってください。書込失敗時はエラーを表示し、生本文をログへ出しません。

## データモデルと取得位置

- Reservation: captureId、CLIクライアントidentity、conversationId。
- CaptureSession: captureId、conversationId、submissionId、runId、rootRunId、parentRunId、終了時刻・結果、容量・不完全フラグ。RootのみなのでrootRunId=runId、parentRunId=null。
- LogicalCall: UUID、不変Root Run ID、試行番号カウンター。ツール結果後の新しいdelegate呼出しは別LogicalCall。
- Attempt: UUID、Run/LogicalCall/attemptNumber、取得時刻、Content-Typeだけ、取得完全性、送信状態、HTTP status、サイズ、SHA-256、原本。Authorization/Cookieを含め、他のヘッダーやURLを収集しません。

経路:

```text
Shell keyboard input
  → ConversationInputGateway / SessionLifecycle（Root ID確定・予約割当）
  → ChatExecutionService / PromptのRunExecutionContext
  → StagnationChatModel / ContextAssembler
  → CHATモデルのCapturingChatModel（Logical Call確定）
  → Spring AI / OpenAI SDK（JSONシリアライズ・SDK retry）
  → ChatStreamTimeout / ShowUiSdkRequestInterceptor
  → CaptureInterceptor（最終本文の有界取得）
  → OkHttp / server
```

既存AgentRunScopeはThreadLocalですが、captureはHTTPスレッドでその値を読んで帰属を推定しません。PromptのtoolContextにあるRunExecutionContext/AgentRunContextと、受付境界で確定したRoot IDを利用します。Reactor Contextを付けるだけではSDK dispatcherへ伝播しないことを実HTTPで確認しています。

SDK 4.49.0のRequestOptions/HttpRequestにはローカル属性・タグの公開口がありません。このため記録対象のLogical Callだけ専用SDKクライアントを生成し、不変IDをcapture interceptorに閉じ込めます。既存OpenAiSetup公開APIで接続・認証・timeout・retry・proxy・custom headers・モデル設定を引き継ぎ、自動設定のHTTPカスタマイザー順序とobservation/tool managerを利用します。同期SDKのasync viewを使用し、完了・取消時にSDKを一度だけcloseします。共有クライアントのexecutor/poolをcloseしません。OFF時は既存delegateを使用し、capture用本文コピーも専用SDK生成も行いません。

専用接続のため記録ON時は接続poolの再利用効率が変わります。将来、任意のSDKクライアント注入・独自model observation convention・追加のモデル生成拡張を導入する場合はfactoryも更新し、設定保持を再検証する必要があります。現行リポジトリの確認済み生成経路に限定したMVPです。

## 状態と再試行

送信状態はPREPARED、SEND_STARTED、HTTP_RESPONSE、SEND_FAILED、CANCELLED、OUTCOME_UNKNOWNです。SEND_STARTEDはchain.proceed開始、HTTP_RESPONSEはレスポンスヘッダー取得を示します。HTTP_RESPONSEはストリーム完了やLLM成功を示しません。レスポンス取得後のstream取消でもHTTP_RESPONSEという事実は維持し、Runの終端は別に扱います。

取得状態はCOMPLETE、SKIPPED_TOO_LARGE、SKIPPED_ONE_SHOT、SKIPPED_UNSUPPORTED、CAPTURE_FAILEDです。完全原本がない場合raw/exportは拒否します。サイズが不明なスキップ記録のsizeは-1です。

SDKの503→200自動retryは同じLogicalCall、Attempt 1/2として2件取得できました。application interceptorに到達するSDK試行だけを数えます。OkHttp内部の接続回復・redirect等がapplication interceptorを再度通らない場合、その内部送信を別Attemptとして捏造しません。上位の新しいモデル呼出しやfallbackは別LogicalCallになります。

Streamingのレスポンスbodyは読みません。購読を追加せず、取消を待機させません。first chunk、完了、途中取消、HTTP errorをON/OFFで検証しています。取消前に本文を取得できない場合、COMPLETEを捏造しません。

one-shot、duplex、サイズ不明のbodyは読みません。既存ShowUIにもone-shot/duplexの先読み回避を追加しました。対象SDKのrepeatable JSON bodyと通常byte-array bodyは有界sinkで取得します。未対応のsink操作はCAPTURE_FAILEDとして本来の送信を継続します。本文は差替えず、記録用のHTTP再送信も行いません。

## 容量と寿命

| 制限 | 固定値 |
| --- | --- |
| 本文1件 | 1,048,576 bytes |
| Root Run本文合計 | 16,777,216 bytes |
| Run内の記録 | 32 Attempts |
| 保存セッション | 最大3（新規受付時に最古の終了済みを削除） |
| 同時記録/予約 | 1 |
| TTL | Run終了後30分（アクセス時と1秒周期で失効確認） |

巨大本文は部分保存しません。Run容量到達後もメタデータを件数上限まで記録し、不完全として表示します。32件を超えた場合にも不完全を表示し、本来のLLM送信は継続します。無制限設定や復元機能はありません。

原本の永続コピーは最大48 MiBです。一時取得の同時数は非待機permitで1に制限し、sinkの各対応write入口で1 MiB超過をコピー前に拒否します。sink、読出byte[]、Storeへの防御コピーは一時的に併存します。メタデータは最大96件、表示は1本文ずつ・深さ100・要素数65,536に制限します。48 MiBはJVM heap全体の上限ではなく、JSON tree・UTF-8復号・整形文字列・SDKが既に作った本文等の固定上限内の追加オーバーヘッドがあります。

削除時は保持byte[]をゼロ埋めし参照を破棄しますが、JVM/GC・過去の防御コピーを含めた物理メモリ消去は保証しません。JVM再起動で消えます。端末scrollback/history、clipboard、export済みファイル、その後のコピーはStore削除の対象外です。

## 検証とTDD記録

初期調査の基準は2026-10-09取得のorigin/main `943a6f5975fa6bba55052f48325d05d3a35bbd22`。最終統合は音声入力由来の識別を保持して `0f251277` 上で実施しました。Java 25、Spring Boot 4.1.1、Spring AI 2.0.1、OpenAI SDK 4.49.0、OkHttp 4.12.0です。追加のテスト依存はありません。JDK HttpServerをローカルHTTPサーバーとして使用しました。

Phase 0ではSDKの同期retry、async streaming、ShowUI変換後について、原本候補と受信bodyのbyte単位一致を確認しました。SHA-256も照合しました。ThreadLocal不伝播とReactor Contextだけでは不十分なこと、公開タグAPIの不在、専用クライアントによるID伝播を別の試験で確認しました。初期ShowUIのone-shot二重消費を再現し、修正後は1回だけ消費する試験へ更新しました。

Phase 1/2/3はStore、HTTP interceptor、CLIの順に先に失敗するテストを実行（未実装クラスによるRed）、最小実装後にGreenを確認して改善しました。キュー取消・遅延executor拒否にも先にRedを確認して修正しました。独立レビューの一時Buffer上限・削除後copy・終端結果・queued cancellation指摘へ対応し、回帰試験を残しました。既存の取消失敗イベントをCANCELLEDへ分類する試験もRed→Greenで修正しました。最初の全回帰（4,230件）で検出した部分Springコンテキストの必須Store注入は任意注入に変更し、機能OFFの既存構成を維持しました。

```powershell
.\mvnw.cmd -q -Pfull "-Dtest=RequestCapturePhaseZeroTest,CapturingChatModelHttpTest,CaptureStoreTest,CaptureInterceptorTest,CaptureCommandTest,CaptureAdmissionTest,CaptureLifecycleTest" test
.\.github\ci\full-test.ps1
```

`CapturingChatModelHttpTest`は実ContextAssembler圧縮、実StagnationChatModelのTool往復、503 retry、並行A/B/background、分割stream/first chunk/取消/HTTP error、機密sentinelの非ログ出力を検証します。捕捉した完全本文は`Arrays.equals`相当のbyte[] equalityとSHA-256で照合します。Store/CLI試験は容量、件数、TTL、予約scope、ID、原本不変、明示確認、export byte一致、上書き防止、削除後非取得を確認します。

最終全回帰はCIと同じ `full-test.ps1` で4,266件、Failures 0、Errors 0、Skipped 1、BUILD SUCCESS（12分09秒）。新規capture関連32件もすべて成功しました。既存のDocumentRendererProcessTest 1件はPlantUML jar未設定によるassumption skipです。必須CIを無効化したりテストを削除したりしていません。

SSE/監査へのcapture本文イベントは実装していません。capture自身が追加するログもありません。既存チャットのメッセージ/ツール結果イベント・運用側の独自HTTPログ設定は別の仕組みです。

## 将来候補

子Run、用途別モデル、送信間diff、圧縮前後の比較、Token使用量分析、モデル設定diff、Web/Desktop UI。今回のMVPには含めません。
