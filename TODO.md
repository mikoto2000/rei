## AI 秘書として最短で追加すべき 3 機能

### 1. タスク管理

目的:
予定管理だけでなく、「やること」を保持して AI が整理・参照できるようにする。

実装タスク:
- [x] `task` コマンド追加
- [x] `task add` `task list` `task done` `task delete` の各サブコマンド追加
- [x] タスク情報を表す `Task` エンティティ追加
- [x] タスク永続化用の SQLite テーブル追加
- [x] タスク CRUD を行う `TaskService` 追加
- [x] 優先度、期限、状態、タグを扱えるようにする
- [x] AI ツールとして `taskList` `taskCreate` `taskComplete` を追加
- [x] 期限順・優先度順で一覧できるようにする
- [x] タスク操作のユニットテスト追加
- [x] `task` コマンドの結合テスト追加
- [x] `task list` のフィルタ機能追加

### 2. 日次ブリーフィング

目的:
その日の予定・未完了タスク・関連ドキュメントをまとめて提示し、秘書らしい先回りを可能にする。

実装タスク:
- [x] `briefing today` コマンド追加
- [x] `BriefingService` 追加
- [x] Google Calendar から当日予定を取得する処理を統合
- [x] タスク一覧から未完了・期限切れタスクを集約する処理を追加
- [x] ベクトルストアから関連文書を引く処理を追加
- [x] 朝の概要、注意点、次アクションを生成するプロンプトを追加
- [x] 予定ゼロ・タスクゼロ時のフォールバック文言を追加
- [x] AI ツールとして `dailyBriefing` を追加
- [x] ブリーフィング生成のサービス層テスト追加
- [x] CLI から日次ブリーフィングを確認する結合テスト追加

### 3. リマインド

目的:
ユーザーが尋ねたときだけでなく、予定や期限に先回りして通知できるようにする。

実装タスク:
- [x] `reminder` コマンド追加
- [x] `reminder add` `reminder list` `reminder delete` の各サブコマンド追加
- [x] リマインド情報を表す `Reminder` エンティティ追加
- [x] リマインド永続化用の SQLite テーブル追加
- [x] リマインドの登録・実行判定を行う `ReminderService` 追加
- [x] 「予定の何分前」「指定日時」の両方を扱えるようにする
- [x] 定期実行で期限到来リマインドを検出するジョブ追加
- [x] 通知方法としてまずは標準出力通知を実装
- [x] AI ツールとして `reminderCreate` `reminderList` を追加
- [x] 時刻計算まわりのテスト追加
- [x] リマインド実行条件の結合テスト追加

## 次に着手すべき 3 機能

### 4a. meeting prep

目的:
会議前に、その会議に必要な予定・関連タスク・関連文書をまとめて確認できるようにする。

実装タスク:
- [ ] `meeting` コマンド追加
- [ ] `meeting prep` サブコマンド追加
- [ ] Google Calendar の予定から対象会議を選択する処理を追加
- [ ] 会議前ブリーフィングを生成する `MeetingPreparationService` 追加
- [ ] 関連タスクと関連文書を会議単位で集約する処理を追加
- [ ] 会議前の概要、注意点、確認事項を生成するプロンプトを追加
- [ ] 予定ゼロ・関連資料ゼロ時のフォールバック文言を追加
- [ ] AI ツールとして `meetingPrepare` を追加
- [ ] 会議準備のサービス層テスト追加
- [ ] CLI から会議準備を確認する結合テスト追加

### 4b. meeting note

目的:
会議中または会議直後のメモを保存し、あとから検索・整理できるようにする。

実装タスク:
- [ ] `meeting note` サブコマンド追加
- [ ] 会議メモを表す `MeetingNote` エンティティ追加
- [ ] 会議メモ永続化用の SQLite テーブル追加
- [ ] 会議メモを保存・一覧する `MeetingNoteService` 追加
- [ ] 予定とメモを紐づける処理を追加
- [ ] ベクトルストアへ会議メモを埋め込む処理を追加
- [ ] AI ツールとして `meetingRecord` を追加
- [ ] 会議メモ保存のサービス層テスト追加
- [ ] CLI から会議メモ保存を確認する結合テスト追加

### 4c. meeting followup

目的:
会議メモから決定事項と次アクションを抽出し、会議後のタスク整理を自動化する。

実装タスク:
- [ ] `meeting followup` サブコマンド追加
- [ ] 会議メモから決定事項・次アクションを抽出する `MeetingFollowupService` 追加
- [ ] 抽出した次アクションを `task` へ変換する処理を追加
- [ ] 会議後サマリーを生成するプロンプトを追加
- [ ] AI ツールとして `meetingFollowup` を追加
- [ ] 会議後整理のサービス層テスト追加
- [ ] CLI から会議後整理を確認する結合テスト追加

### 5. 承認つき実行フロー

目的:
予定追加、タスク更新、通知送信などの副作用を伴う操作を、実行前に確認できるようにして安全に代理実行できるようにする。

実装タスク:
- [ ] 実行前確認を表す `PendingAction` エンティティ追加
- [ ] `PendingAction` 永続化用の SQLite テーブル追加
- [ ] 承認待ちアクションを管理する `ApprovalService` 追加
- [ ] `approve` コマンド追加
- [ ] `approve list` `approve show` `approve run` `approve reject` の各サブコマンド追加
- [ ] 既存の `schedule` `task` `reminder` 操作を承認フロー経由で実行できるようにする
- [ ] AI が副作用を伴う操作を直接実行せず、承認待ちとして登録するモードを追加
- [ ] 承認時に実行されるコマンド内容のプレビュー生成を追加
- [ ] 承認待ちの有効期限と失効処理を追加
- [ ] AI ツールとして `approvalList` `approvalCreate` `approvalRun` `approvalReject` を追加
- [ ] 承認フローのサービス層テスト追加
- [ ] CLI から承認・却下・実行を確認する結合テスト追加

### 6. 通知チャネル拡張

目的:
リマインドや日次ブリーフィングを、標準出力だけでなく利用環境に応じたチャネルへ確実に届けられるようにする。

実装タスク:
- [ ] 通知送信を抽象化する `Notifier` インターフェース追加
- [ ] 標準出力通知を `StdoutNotifier` として切り出し
- [ ] Windows 向け通知実装追加
- [ ] Linux 向け通知実装追加
- [ ] macOS 向け通知実装追加
- [ ] 利用可能な通知チャネルを自動判定する `NotifierResolver` 追加
- [ ] 通知方式を設定する `NotificationProperties` 追加
- [ ] `reminder` ジョブから `Notifier` を呼ぶように変更
- [ ] `briefing today` を OS 通知へ送れるオプション追加
- [ ] 通知失敗時に標準出力へフォールバックする処理を追加
- [ ] 通知チャネル選択のユニットテスト追加
- [ ] OS 通知失敗時フォールバックの結合テスト追加

## 学習補助向けに最初に追加すべき 3 機能

### 7. 学習ログ

目的:
何をどれだけ学んだか、どこで詰まったか、次に何をやるかを残せるようにする。

実装タスク:
- [ ] `study` コマンド追加
- [ ] `study log` `study list` の各サブコマンド追加
- [ ] 学習記録を表す `StudyLog` エンティティ追加
- [ ] 学習ログ永続化用の SQLite テーブル追加
- [ ] 学習ログを登録・一覧する `StudyLogService` 追加
- [ ] 科目、教材、学習時間、理解度、メモを扱えるようにする
- [ ] AI ツールとして `studyLogCreate` `studyLogList` を追加
- [ ] `briefing today` で前回学習内容と未完了学習を出せるようにする
- [ ] 学習ログのサービス層テスト追加
- [ ] `study` コマンドの結合テスト追加

### 8. 復習スケジューリング

目的:
学習した内容に対して、適切なタイミングで復習を促せるようにする。

実装タスク:
- [ ] `study review` サブコマンド追加
- [ ] 復習予定を表す `ReviewSchedule` エンティティ追加
- [ ] 復習予定永続化用の SQLite テーブル追加
- [ ] 復習タイミングを計算する `ReviewScheduleService` 追加
- [ ] `study log` 登録時に復習候補を自動生成する処理を追加
- [ ] `reminder` と連携して復習通知を作れるようにする
- [ ] 「明日」「3日後」「7日後」などの基本ルールを実装
- [ ] AI ツールとして `reviewScheduleCreate` `reviewScheduleList` を追加
- [ ] `briefing today` で当日の復習候補を出せるようにする
- [ ] 復習時刻計算のサービス層テスト追加
- [ ] CLI から復習予定を確認する結合テスト追加

### 9. 問題生成

目的:
ノートや教材から確認問題を作り、理解度確認と定着を助ける。

実装タスク:
- [ ] `study quiz` サブコマンド追加
- [ ] 問題を表す `StudyQuestion` エンティティ追加
- [ ] 問題履歴を保存する SQLite テーブル追加
- [ ] ベクトルストアから関連資料を取得して問題化する `QuizService` 追加
- [ ] 一問一答、穴埋め、要点確認の 3 種類を生成できるようにする
- [ ] 回答と正答、解説を保存できるようにする
- [ ] AI ツールとして `studyQuizGenerate` `studyQuizAnswer` を追加
- [ ] 間違えた問題を苦手分野として記録する処理を追加
- [ ] 問題生成のサービス層テスト追加
- [ ] CLI から問題生成と回答確認を行う結合テスト追加

## 書籍活用向けの最小機能

### 10. book import

目的:
本や教材を章管理なしで取り込み、あとから検索・要約・質問回答に使える状態を作る。

実装タスク:
- [ ] `book` コマンド追加
- [ ] `book import` `book list` の各サブコマンド追加
- [ ] 書籍メタ情報を表す `Book` エンティティ追加
- [ ] 書籍チャンクを表す `BookChunk` エンティティ追加
- [ ] `Book` 永続化用の SQLite テーブル追加
- [ ] `BookChunk` 永続化用の SQLite テーブル追加
- [ ] txt / markdown / pdf から本文テキストを取得する `BookImportService` 追加
- [ ] 固定長チャンクへ分割する処理を追加
- [ ] ベクトルストアへ `bookId` `title` `chunkIndex` 付きで保存する処理を追加
- [ ] 書籍取り込みのサービス層テスト追加
- [ ] `book import` の結合テスト追加

### 11. book ask

目的:
対象書籍に限定して質問回答し、この本で何が述べられているかを確認できるようにする。

実装タスク:
- [ ] `book ask` サブコマンド追加
- [ ] 書籍限定の検索と回答生成を行う `BookQaService` 追加
- [ ] `bookId` で検索対象を絞り込む処理を追加
- [ ] 取得チャンクだけを根拠に回答するプロンプトを追加
- [ ] 根拠が不足する場合のフォールバック文言を追加
- [ ] 回答に参照チャンク番号を含める処理を追加
- [ ] AI ツールとして `bookAsk` を追加
- [ ] 書籍限定検索のサービス層テスト追加
- [ ] `book ask` の結合テスト追加

### 12. book summarize

目的:
取り込んだ本や教材の要点を短時間で把握できるようにする。

実装タスク:
- [ ] `book summarize` サブコマンド追加
- [ ] 書籍要約を生成する `BookSummaryService` 追加
- [ ] 対象書籍のチャンク一覧を取得する処理を追加
- [ ] 要点、重要キーワード、次に読むべき箇所を返すプロンプトを追加
- [ ] 長い書籍に備えて段階的要約へ拡張しやすい構成にする
- [ ] AI ツールとして `bookSummarize` を追加
- [ ] 書籍要約のサービス層テスト追加
- [ ] `book summarize` の結合テスト追加

## IT ニュース検索・要約

### 13. IT ニュース検索

目的:
AI やソフトウェア開発、クラウド、セキュリティなどの IT 系ニュースをまとめて収集できるようにする。

実装タスク:
- [ ] `news` コマンド追加
- [ ] `news it` サブコマンド追加
- [ ] IT ニュース検索条件を表す `ItNewsQuery` 追加
- [ ] Web 検索を使って IT ニュース候補を取得する `ItNewsService` 追加
- [ ] `topic` `limit` を指定できるようにする
- [ ] `since` または `days` を指定できるようにする
- [ ] `ai` `java` `security` `cloud` などの既定トピックを扱えるようにする
- [ ] タイトル、URL、要約、公開日時を返す `ItNewsItem` 追加
- [ ] ソースの重複 URL を除外する処理を追加
- [ ] 新着順で並べ替える処理を追加
- [ ] AI ツールとして `itNewsSearch` を追加
- [ ] IT ニュース検索のサービス層テスト追加
- [ ] `news it` の結合テスト追加

### 14. IT ニュース要約

目的:
取得した IT ニュースを短時間で把握できるようにし、何が重要かを先に理解できるようにする。

実装タスク:
- [ ] `news it --summarize` オプション追加
- [ ] IT ニュース要約を生成する `ItNewsBriefingService` 追加
- [ ] 取得したニュース一覧から重要トピックを抽出する処理を追加
- [ ] 類似ニュースをまとめる処理を追加
- [ ] 重要度順に並べる処理を追加
- [ ] 「何が起きたか」「誰に関係あるか」「実務影響」を返すプロンプトを追加
- [ ] 対象読者別の観点を返せるようにする
- [ ] 要約に元記事 URL 一覧を含める処理を追加
- [ ] AI ツールとして `itNewsBrief` を追加
- [ ] ニュース要約のサービス層テスト追加
- [ ] `news it --summarize` の結合テスト追加

### 15. 記事本文取得による高品質要約

目的:
検索結果のスニペットだけでなく記事本文をもとに要約し、精度を上げる。

実装タスク:
- [ ] URL から記事本文を取得する `WebFetchService` 追加
- [ ] `news fetch` サブコマンド追加
- [ ] 本文取得成功時のみ本文ベース要約を使う
- [ ] 失敗時は検索スニペット要約へフォールバックする
- [ ] 取得した本文から要点を抽出する処理を追加
- [ ] 対応しやすいニュースサイトから段階的に精度改善する
- [ ] 重複記事や要約済み記事のキャッシュ方針を追加
- [ ] AI ツールとして `webFetch` を追加
- [ ] 本文取得のサービス層テスト追加
- [ ] 本文ベース要約の結合テスト追加

## ベクトルストア操作の明示機能

### 13. vector document ops

目的:
文書をベクトルストアへ明示的に追加・検索・一覧・削除できるようにし、RAG 用データをユーザーが直接管理できるようにする。

実装タスク:
- [x] 現行の `embed` コマンドを整理し、`embed add` `embed search` `embed list` `embed delete` の各サブコマンド構成へ移行する
- [x] 既存の単発 `embed` 実行を `embed add` のエイリアスとして維持するか決め、互換方針を README に明記する
- [x] ベクトルストア操作をまとめる `VectorDocumentService` を追加する
- [x] 文書単位の識別子として `docId` を導入する
- [x] 埋め込み時に各 chunk の metadata へ `docId` `source` `chunkIndex` `ingestedAt` を保存する
- [x] `source` を正規化する処理を追加し、同一ファイルの再登録判定に使えるようにする
- [x] `embed add <files...>` で txt / markdown / pdf を読み込み、chunk 分割して保存する処理を `VectorDocumentService` へ移す
- [x] `embed add` 実行時に保存件数、chunk 数、対象ファイルを表示する
- [x] `embed add` の重複登録ポリシーを決める
- [x] 重複登録ポリシーに従い、同一 `source` の再登録時に上書き・スキップ・追加のいずれかを選べるようにする
- [x] ベクトルストア保存処理をサービス層へ寄せ、追加・削除後は必ず永続化する
- [x] `embed search <query>` を追加する
- [x] `embed search` で `topK` と `similarityThreshold` を指定できるようにする
- [x] `embed search` の結果に `docId` `source` `chunkIndex` `score` `snippet` を表示する
- [x] `embed search` で source 単位の絞り込みができるようにする
- [x] `embed list` を追加する
- [x] `embed list` で登録済み文書を `docId` `source` `chunk 数` `ingestedAt` 単位で集約表示する
- [ ] `embed list` で source の重複や欠損 metadata を検出できるようにする
- [x] `embed delete --doc-id <id>` を追加する
- [x] `embed delete --source <path>` を追加する
- [x] `embed delete` で対象 doc の全 chunk をまとめて削除する
- [ ] metadata が不足している既存データ向けに移行方針を決める
- [ ] 既存データ移行方針に従い、再取り込みを促すか、可能な範囲で旧データに暫定 `docId` を付与する処理を追加する
- [ ] `embed stats` または `embed doctor` を追加するか判断し、必要ならベクトルストア状態確認コマンドを追加する
- [x] AI ツールとして `vectorDocumentSearch` `vectorDocumentList` `vectorDocumentDelete` の追加要否を決める
- [ ] AI ツールを追加する場合は、副作用のある削除操作に承認が必要か整理する
- [x] README に `embed add/search/list/delete` の使い方を追記する
- [x] system prompt に「文書検索は vector store を優先するケース」を必要に応じて追記する
- [x] `VectorDocumentService` のユニットテストを追加する
- [x] metadata 付き登録処理のテストを追加する
- [x] source / docId 指定削除のテストを追加する
- [x] `embed search` の結合テストを追加する
- [x] `embed list` の結合テストを追加する
- [x] `embed delete` の結合テストを追加する

## ベクトルストアの SQLite 化

### 14. ベクトルストア抽象化

目的:
現在の `SimpleVectorStore` 直結実装を、SQLite バックエンドへ差し替えられる構造に整理する。

実装タスク:
- [x] `SimpleVectorStore` 依存箇所を洗い出し、移行対象一覧を固定する
- [x] `VectorStoreConfiguration` の戻り値型を `VectorStore` へ寄せる方針を固める
- [x] `AiConfiguration` の `QuestionAnswerAdvisor` 連携を `VectorStore` / `VectorStoreRetriever` 前提に変更する
- [x] `BriefingService` の依存を `SimpleVectorStore` から `VectorStoreRetriever` へ変更する
- [x] `VectorDocumentService` の依存を `SimpleVectorStore` から `VectorStore` へ変更する
- [x] `SimpleVectorStore.save(...)` 前提の保存処理を抽象化し、ストア固有処理を切り離す
- [x] `VectorStorePaths` の JSON ファイル前提 API を整理し、SQLite 用パス追加方針を決める
- [x] 既存ユニットテストを `VectorStore` 抽象前提へ更新する
- [x] 抽象化リファクタの回帰テストを追加する

### 15. SQLite ベクトルストア実装

目的:
SQLite に文書メタデータと埋め込みベクトルを永続化できる `VectorStore` 実装を追加する。

実装タスク:
- [x] SQLite ベースの `VectorStore` 実装クラスを追加する
- [x] 初期実装は `documents` と `document_chunks` の 2 テーブル構成にする
- [x] `documents` テーブルのスキーマを定義する
- [x] `document_chunks` テーブルのスキーマを定義する
- [x] 検索結果の返却単位を chunk 単位にすることを明記する
- [x] `documents.doc_id` と `document_chunks.chunk_id` の一意制約を定義する
- [x] `documents` / `document_chunks` の外部キー制約と cascade delete 方針を定義する
- [x] `docId` `source` `chunkId` 向け index を追加する
- [x] 文書本文、metadata、embedding を保存する insert 処理を実装する
- [x] chunk ID 指定で削除する delete 処理を実装する
- [x] `docId` 指定で削除する delete 処理を実装する
- [x] `source` 指定で削除する delete 処理を実装する
- [x] 再登録時の置換方針を `source` 単位の delete + insert で統一する
- [x] `SearchRequest` を受けて候補 chunk を取得する read 処理を実装する
- [x] `topK` と `similarityThreshold` を適用する処理を追加する
- [x] metadata による `source` / `docId` 絞り込みを適用する処理を追加する
- [x] 類似度指標を cosine similarity に固定する
- [x] ベクトル正規化の実施タイミングを決める
- [x] embedding 次元不一致時のエラー方針を決める
- [x] 類似度計算を Java 側で行う暫定実装を追加する
- [x] 埋め込みベクトルの保存形式を JSON か BLOB かで決めて統一する
- [x] 埋め込みベクトル保存形式の判断基準として、実装容易性、読み出し性能、サイズ効率、デバッグ容易性を明記する
- [x] 文書登録を 1 文書 1 トランザクションで実行する
- [x] 文書削除を関連行込みで 1 トランザクションで実行する
- [x] 部分失敗時のロールバック方針を定義する
- [x] SQLite 初期化処理を `DataSourceConfiguration` と整合する形で追加する
- [x] SQLite ファイル破損時の扱いを定義する
- [x] SQLite ロック発生時の扱いを定義する
- [x] metadata 欠損データの扱いを定義する
- [x] 存在しない chunk / doc 削除時の扱いを定義する
- [x] スキーマ初期化のユニットテストを追加する
- [x] 複数文書・複数 chunk 登録テストを追加する
- [x] `docId` / `source` 絞り込み検索テストを追加する
- [x] `topK` と `similarityThreshold` の境界値テストを追加する
- [x] cascade delete / 関連削除テストを追加する
- [x] 再登録時の置換テストを追加する
- [x] 空ストア検索テストを追加する
- [x] embedding 次元不一致テストを追加する

### 16. 既存ベクトル文書機能の載せ替え

目的:
既存の文書埋め込み・検索・削除機能を SQLite バックエンド前提へ載せ替える。

実装タスク:
- [x] `VectorDocumentService` の保存先を SQLite ベクトルストアへ切り替える
- [x] `docId` / `source` / `chunkIndex` / `ingestedAt` を SQLite 側で保持するようにする
- [x] `embed add` の再登録時に source 単位で置換できることを確認する
- [x] `embed search` の結果整形が載せ替え後も維持されるようにする
- [x] `embed list` の source 集約表示が載せ替え後も維持されるようにする
- [x] `embed delete --doc-id` が載せ替え後も維持されるようにする
- [x] `embed delete --source` が載せ替え後も維持されるようにする
- [x] AI ツール `vectorDocumentAdd` `vectorDocumentSearch` `vectorDocumentList` `vectorDocumentDeleteByDocId` `vectorDocumentDeleteBySource` の回帰テストを追加する
- [x] `QuestionAnswerAdvisor` の検索結果が載せ替え後も使えることを確認する
- [x] `dailyBriefing` の関連文書取得が載せ替え後も使えることを確認する
- [x] `SqliteVectorStore` の検索で全件走査を避けるため、lexical prefilter か候補件数絞り込みを追加する
- [x] `VectorDocumentService` の doc 単位集約を `max score` のみから複合スコア方式へ改善する
- [x] snippet 生成を query 近傍切り出しから「最も一致する文」優先へ改善する
- [x] 同一文書の近接 `chunkIndex` をまとめて返す前後チャンク統合ロジックを追加する
- [x] ベクトル類似度に語句一致を混ぜるハイブリッド検索を検討・実装する
- [x] タイトル、見出し、ファイル種別、タグなど検索改善に使うメタデータ拡張を追加する
- [x] 文書種別ごとに chunk size / overlap / splitter 戦略を切り替えられるようにする
- [x] 上位候補の再ランキング層を追加するか判断し、必要なら rule-based または LLM rerank を実装する

### 17. 旧 JSON 永続化コードの撤去

目的:
未リリースのうちに JSON ベース永続化を整理し、SQLite 前提の実装へ単純化する。

実装タスク:
- [x] `vector-store.json` 前提の保存・読込コードを削除する
- [x] `vector-documents.json` 前提の保存・読込コードを削除する
- [x] `VectorStorePaths` の JSON 用パス API を削除または SQLite 用へ置き換える
- [x] README から JSON ベクトルストア前提の説明を削除する
- [x] 旧 JSON 永続化コード削除後の回帰テストを追加する

### 18. 設定・運用・ドキュメント整備

目的:
SQLite ベクトルストアを安全に運用できる設定と説明を揃える。

実装タスク:
- [ ] ベクトルストア種別を切り替える `rei.vector-store.type` 設定を追加する
- [ ] SQLite ベクトル DB ファイルの保存先設定を追加する
- [ ] 開発時は `simple` / 本番は `sqlite` のような切り替え方針を決める
- [ ] 起動時ログに利用中ベクトルストア種別を出すようにする
- [x] README に SQLite ベクトルストアの構成と制約を追記する
- [x] 類似度計算を Java 側で行う場合の性能制約を README に明記する
- [ ] `score > 0` を返却条件とする現行仕様を README またはコードコメントに明記する
- [ ] 将来的に pgvector 等へ差し替えやすい設計方針を TODO または README に追記する
- [ ] Native Image 影響の有無を確認する
- [ ] 起動確認と主要コマンドの手動確認手順を追記する
