# Work Context / プロジェクトの作業引き継ぎ

Work Contextはプロジェクト固有の作業状態です。会話をまたいで、目的、現在の作業、決定と理由、完了報告、未完了・未検証、ブロッカー、次のアクション、関連成果物を参照できます。保存された次のアクションは参考情報であり、読み込むだけでは実行しません。

## Shell

```text
/project cd "F:\project\rei"
/work
/work show
/work update
/work history
/session new 続きの確認
```

`/work`と`show`は引き継ぎと項目ID・状態・根拠を表示します。`update`は選択中Sessionの会話と取得できるTool・ファイルイベントから候補を抽出し、既存状態へ統合します。UI上のプロジェクトが変わっていても、保存先はSession作成時のProject IDです。Session未選択、未知のSession、更新対象のディレクトリが存在しない場合は案内を返します。Work Context操作ではプロジェクトディレクトリを作成しません。

手動保存では実行中Runの入力と確認できた部分進捗も扱います。実行中Runを処理済みにせず、終端状態になった後の更新で結果を反映します。自動更新とHTTP更新は確定Turnが対象です。長い会話では複数回実行してください。入力予算を超えた場合は保存せずエラーになり、`max-turns`を下げて再実行できます。

`history`は直近100リビジョンの番号、日時、項目数を表示します。過去スナップショットの内容はChat ToolまたはWeb APIで取得できます。

## 自然言語

```text
このプロジェクト、どこまで進んだ？ 根拠も教えて。
前回の続きに必要な情報を教えて。
今の作業状況を引き継ぎとして保存して。
次は何をすればいい？ まだ実行せず説明して。
この決定事項を「キャンセル後の入力は新しいRunとして扱う」に修正して。
Native確認のタスクを完了にして。
そのタスクを再開して。
この決定は撤回して、理由も残して。
revision 2の引き継ぎを見せて。
```

Chatに`workContextGet / workContextUpdate / workContextEdit / workContextHistory / workContextRevision`を登録しています。取得・履歴は1〜100件の有界な操作です。訂正には最新revisionと安定した項目IDを使います。古いrevisionでの訂正は競合として拒否されるため、最新情報を取得してやり直してください。

通常はRunに固定されたプロジェクトを対象にします。別プロジェクトは、ユーザーの現在の依頼にProject IDまたはフルパスを明示してください。表示名だけでは選びません。

## 情報の確実性と履歴

項目の種類は`PURPOSE / CURRENT_WORK / DECISION / COMPLETED_WORK / PENDING / VERIFICATION / BLOCKER / NEXT_ACTION / ARTIFACT`、状態は`OPEN / COMPLETED / WITHDRAWN / SUPERSEDED / UNCONFIRMED`です。

`certainty`は`USER`（ユーザーが明示）、`TOOL`（利用できる結果で確認）、`ASSISTANT`（アシスタントの報告）、`INFERENCE`（推定・提案）を区別します。根拠の取得元も個別に保存します。アシスタントによる実装完了の報告は、テストや受入条件の達成確認ではありません。検証成功をアシスタント報告だけで抽出した場合は`UNCONFIRMED`になります。

新しい会話で言及しなかっただけでは項目を削除しません。完全一致は正規化して重複を抑え、意味的な一致は既存項目IDを提示したLLM抽出で照合します。明示的な方針変更は旧項目を`SUPERSEDED`にして置換先を残します。訂正・完了・再開・撤回も過去スナップショットで追跡できます。ユーザー訂正を古い推定で上書きせず、古い観測日時の更新でも既存項目を戻しません。

Session ID、Turn ID（既存設計ではRun IDと同値）、Run ID、利用できるTool Call ID、イベントにあるファイルパス、観測日時、取得日時、根拠の有界な本文を保存します。存在しない参照や根拠に合わないcertaintyをLLMが返した場合は更新全体を拒否します。

Gitのディレクトリ・ブランチ・コミットは新Run開始時に取得できた値をTurn metadataに記録します。以前のTurnでは更新時点の値を使い、取得日時を表示します。要約は保存情報の条件と最終更新日時を示し、現在のブランチ／コミットが異なる場合やGit未確認を明記します。コミットが同じでも未コミットのファイル内容が同じとは保証しません。

## 設定

```yaml
rei:
  work-context:
    auto-update: false
    auto-present: true
    max-context-tokens: 1200
    max-input-tokens: 12000
    timeout-seconds: 120
    max-turns: 20
```

| 設定 | 初期値 | 環境変数 |
| --- | --- | --- |
| 自動更新 | false | `REI_WORK_CONTEXT_AUTO_UPDATE` |
| 再開時の自動提示 | true | `REI_WORK_CONTEXT_AUTO_PRESENT` |
| CHAT注入予算 | 1200推定tokens | `REI_WORK_CONTEXT_MAX_CONTEXT_TOKENS` |
| 抽出入力予算（指示・Schemaを含む） | 12000推定tokens | `REI_WORK_CONTEXT_MAX_INPUT_TOKENS` |
| 抽出timeout | 120秒 | `REI_WORK_CONTEXT_TIMEOUT_SECONDS` |
| 1回の最大Turn数 | 20 | `REI_WORK_CONTEXT_MAX_TURNS` |

設定変更は再起動後に反映します。手動更新は自動更新が無効でも利用できます。抽出には既存の`rei.llm.features.memory`モデル設定を再利用し、Sleepの有効化や処理済み位置には依存しません。

自動更新を有効にすると、成功・失敗・キャンセルのTurn終端情報を保存した後、専用キューで更新します。失敗・キャンセルしたタスク全体を完了とは扱いません。抽出・保存の失敗で元のRun結果は変更しません。イベントは`work_context.update.started / work_context.updated / work_context.update.failed`です。失敗やキュー満杯の場合は`/work update`で再処理できます。強制終了時の処理は保証しません。

## 再開とコンテキスト

Shellのプロジェクト選択・切替、新規Session、Native Clientの会話選択で、現在の作業・前回の進捗・未解決点・次のアクション・最終更新日時を短く表示します。アプリ内では同じSession／会話に繰り返し提示しません。引き継ぎがなければ簡潔に案内します。

通常CHATには最新リビジョンだけを注入し、現在の作業、決定、ブロッカー、次のアクションを優先します。ヘッダー・message overheadも予算に含め、長すぎる項目はスキップします。ContextAssemblerの最終予算にも含め、hard limit超過時は会話を削る前に補助Work Contextを外します。Working Set、Rolling Summary、Conversation Historyを書き換えません。

## 保存と既存機能

保存先は既存の`<rei-data-dir>/memory-consolidation.db`です。`work_context_heads`が最新revisionを指し、`work_context_revisions`がimmutable JSONスナップショットを保持します。ProjectRegistryの実ディレクトリ正規化・UUIDを使い、同名の別ディレクトリやWindows表記差は既存プロジェクト管理と同じように扱います。

最新ポインタとスナップショットを同一SQLiteトランザクションで更新します。同一プロジェクトの更新をサービスで直列化し、他プロセスや古い読取状態からの更新もrevision比較で拒否します。失敗・キャンセル時は直前の正常な状態を保ちます。処理済み終端Run IDは保存され、再処理を抑えます。

長期記憶のmemories、Sleepの履歴／処理済み位置、TaskStateや実行キューを操作しません。一般的な好み・習慣とプロジェクト進捗を混在させません。Auto Sleep、タスク再実行、プロセス復元、全履歴移行はありません。

## Web API / Native Client

既存Web APIを有効にしたときだけ公開し、すべて既存のBearer認証が必要です。

| 操作 | パス |
| --- | --- |
| 最新スナップショット | `GET /api/v1/projects/{projectId}/work-context` |
| 短い要約・自動提示設定 | `GET /api/v1/projects/{projectId}/work-context/summary` |
| 履歴（既定20、最大100） | `GET /api/v1/projects/{projectId}/work-context/history?limit=20` |
| 過去リビジョン | `GET /api/v1/projects/{projectId}/work-context/history/{revision}` |
| Sessionの確定Turnから更新 | `POST /api/v1/sessions/{sessionId}/work-context/update` |

Native Workspaceの「Work Context / 引き継ぎ」「Work Context 更新履歴」「会話から引き継ぎを保存」はProject ID／Session IDを指定して利用します。会話選択時の短い提示はサーバーの`auto-present`に従います。詳細取得や項目の訂正・完了・再開・撤回はチャットからも依頼できます。

## 制約

- LLMの意味判断はSchemaだけでは保証できません。certainty、出典、過去revisionで確認してください。
- 根拠本文は1件3000文字まで、Tool等は既存イベントの直近1000件から対象Runのものを取得します。切り詰めを明記し、省略部分の結果を確認済みとしないよう抽出へ指示します。古いイベントにないTool結果は捏造しません。
- 入力上限超過は安全に失敗し、未処理位置を進めません。Turn数や予算を調整できます。
- 抽出に渡す既存項目は、未撤回・未置換の新しい項目を入力予算の1/3以内で選びます。保存された未完了項目は削除しません。予算から外れた古い言い換えまで完全に意味的重複を検出する保証はありません。
- 自動更新キューは共通FIFO・1worker・最大256件です。同一プロジェクトの保存は手動更新も含め直列化します。他プロセスとの競合は再試行が必要です。
- Nativeの専用項目編集画面、モバイル実機検証、強制終了直前の更新保証はありません。
