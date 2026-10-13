# Shell の停止・失敗理由の確認記録

RootCommand に登録された全コマンド（機能フラグで無効になるものを含む）、ネストしたサブコマンド、Session の new/resume、軽量 CLI のローカルコマンドと RemoteCommands の全ルートを確認した。確認対象は引数エラー、例外を捕捉して返す経路、例外を上位へ送る経路、キャンセル、非同期の失敗イベントである。外部サービスへの実接続で全操作を実行する検証ではなく、実装確認と失敗を再現する回帰テストである。

## 追加した表示

- Activity、Session、mode、timing、doctor、capture、ChatGPT、Dot、paper、storage の汎用エラーに例外の原因を追加。
- コマンド共通の例外ハンドラでコマンド名と原因連鎖を表示。キャンセルは終了コード130、通常の失敗は既存の終了コード指定を尊重する。SessionBusy の復旧手順も維持する。
- メッセージのない例外は例外型を表示。認証情報をマスキングしてから制御文字を除去し、600文字に制限する。
- Run キャンセルの具体的な理由・コード、Goal の理由、Dependency の失敗・キャンセル理由、Task と Context 圧縮の失敗を表示。
- /cancel と Esc は停止要求の受付結果を表示。画像生成・検索・Sleep・Work の中断、原本表示・保存の確認取消、旧モデル解放の失敗に説明を追加。
- 非同期画像生成・要約と Run runner の予期しない例外で原因を落とさない。
- 軽量 CLI はイベントの失敗・キャンセルを読みやすく表示し、接続失敗・切断・認証失敗に原因を表示。Ctrl-C による入力取消と表示切断も説明する。

既存の具体的な引数案内、承認待ちの再開手順、シェルプロセスの終了コード、失敗情報のある結果表示は活用する。Doctor は失敗した段階と例外型を表示し、接続URLなどを含み得る例外本文は表示しない。Memory や Work Context の自動処理で本文をイベントに載せない方針は維持し、イベントが原因を持たない場合は状態・IDまたは「原因の詳細がありません」を表示する。理由を推測して表示しない。

## 確認対象の一覧

各ファイルの @Command メソッドとネストしたコマンドを含む。表の名前はソース上のローカル名で、ネストしたものは親コマンドに続けて指定する。

| 実装（src/main/java/dev/mikoto2000/rei 以下） | コマンド名・サブコマンド名 |
| --- | --- |
| `activity/ActivityCommand.java` | `/activity`, `/summary`, `/weekly`, `/monthly`, `/coaching`, `/context`, `/behavior`, `/classification` |
| `application/cli/ActivityRequest.java` | `/activity`, `/summary`, `/weekly`, `/monthly`, `/context`, `/coaching`, `/behavior`, `/classification` |
| `attention/AttentionCommand.java` | `/attention` |
| `bluesky/command/BskyCommand.java` | `/bsky`, `/reply`, `/history-users`, `/history` |
| `briefing/command/BriefingCommand.java` | `/briefing`, `/today` |
| `chatgpt/ChatGptCommand.java` | `/chatgpt`, `/login`, `/status`, `/models`, `/logout`, `/infer` |
| `checkpoint/CheckpointCommand.java` | `/checkpoint`, `/status`, `/cleanup` |
| `checkpoint/ResumeCommand.java` | `/resume`, `/list`, `/show`, `/abandon` |
| `core/command/ConfigCommand.java` | `/config`, `/path`, `/init` |
| `core/command/DocumentCommand.java` | `/document` |
| `core/command/EmbedCommand.java` | `/embed`, `/add`, `/search`, `/list`, `/delete` |
| `core/command/ModelCommand.java` | `/model` |
| `core/command/ModelsCommand.java` | `/models` |
| `core/command/ProfileCommand.java` | `/profile`, `/path`, `/summary`, `/chart`, `/mermaid` |
| `core/command/ProjectCommand.java` | `/project`, `/add`, `/remove`, `/list`, `/cd` |
| `core/command/RepairCommand.java` | `/repair` |
| `core/command/SearchCommand.java` | `/search` |
| `core/command/ShCommand.java` | `/sh` |
| `core/command/StorageCommand.java` | `/storage`, `/retention`, `/plan`, `/approve`, `/history`, `/apply`, `/restore`, `/approve-purge`, `/purge`, `/policy`, `/auto`, `/propose`, `/approve`, `/disable`, `/history`, `/maintenance`, `/status`, `/checkpoint`, `/incremental-vacuum`, `/vacuum`, `/status` |
| `core/dependency/DependencyCommand.java` | `/dependency` |
| `core/policy/ApprovalCommand.java` | `/approval` |
| `doctor/DoctorCommand.java` | `/doctor` |
| `dot/DotCommand.java` | `/dot` |
| `externalagent/BeginnerReviewCommand.java` | `/material-review` |
| `externalagent/BeginnerReviewRequest.java` | `/material-review` |
| `externalagent/ExternalAgentCommand.java` | `/agent` |
| `feed/command/FeedCommand.java` | `/feed`, `/import-opml`, `/add`, `/list`, `/edit`, `/delete`, `/update`, `/summary`, `/item`, `/list`, `/summarize` |
| `goal/GoalCommand.java` | `/goal` |
| `googlecalendar/command/ScheduleCommand.java` | `/schedule`, `/auth`, `/refresh-token`, `/list`, `/add` |
| `image/command/GenerateCommand.java` | `/generate` |
| `image/command/ImageCommand.java` | `/image` |
| `interest/command/InterestCommand.java` | `/interest`, `/list`, `/discover` |
| `llm/capture/LlmCaptureCommand.java` | `/llm`, `/capture` |
| `memory/command/MemoryCommand.java` | `/memory`, `/list`, `/search`, `/forget`, `/show`, `/export`, `/summarize`, `/consolidate` |
| `memory/command/SleepCommand.java` | `/sleep`, `/preview`, `/status`, `/history`, `/requests`, `/cancel-request`, `/auto`, `/status`, `/resume` |
| `paper/PaperCommand.java` | `/paper` |
| `paper/PaperCommandRequest.java` | `/paper` |
| `reflection/ReflectionCommand.java` | `/reflection` |
| `reminder/command/ReminderCommand.java` | `/reminder`, `/add`, `/list`, `/delete` |
| `skills/command/SkillCommand.java` | `/skill`, `/list`, `/show`, `/reload` |
| `subagent/SubAgentCommand.java` | `/subagent`, `/run`, `/list`, `/show`, `/reload`, `/validate`, `/init` |
| `summarize/command/SummarizeCommand.java` | `/summarize` |
| `task/command/TaskCommand.java` | `/task`, `/auth`, `/add`, `/list`, `/done`, `/delete` |
| `temporal/TimerCommand.java` | `/timer` |
| `timing/TimingCommand.java` | `/timing` |
| `ui/shell/CancelCommand.java` | `/cancel` |
| `ui/shell/ChatCommand.java` | `/chat` |
| `ui/shell/ConversationModeCommand.java` | `/mode`, `/auto`, `/normal`, `/conversation`, `/status` |
| `ui/shell/HistoryCommand.java` | `/history`, `/list`, `/show`, `/search` |
| `ui/shell/NewConversationCommand.java` | `/new` |
| `ui/shell/ResumeConversationCommand.java` | `/switch` |
| `ui/shell/RootCommand.java` |  |
| `ui/shell/RunsCommand.java` | `/runs` |
| `ui/shell/SessionCommand.java` | `/session`, `/end`, `/show`, `/list` |
| `voice/VoiceCommand.java` | `/voice`, `/correction`, `/confirm`, `/on`, `/off`, `/status`, `/devices`, `/test`, `/config`, `/features`, `/models`, `/info`, `/status`, `/verify`, `/install`, `/cancel`, `/device`, `/set`, `/pending`, `/cancel` |
| `workcontext/WorkCommand.java` | `/work`, `/show`, `/update`, `/history` |

軽量 CLI のローカルコマンド: /help、/exit、/quit、/project、/session、/chat、/run-mode、/cancel、/runs、/watch、/receipt、/input-history、/paste、/sh。RemoteCommands.NAMES の全コマンドと routeDirect の引数解析・固定APIアダプタも確認した。

## 検証

本体: `./mvnw.cmd test -Pfull`。軽量 CLI: `./mvnw.cmd -f terminal/pom.xml test`。
追加テストは原因連鎖・空メッセージ・循環原因・マスキング・表示長制限、Activity と Session の失敗理由、停止要求の受付結果、例外ハンドラの終了コード、Run キャンセル理由、Context 圧縮失敗の表示とストリーム境界、軽量 CLI の失敗表示を確認する。
