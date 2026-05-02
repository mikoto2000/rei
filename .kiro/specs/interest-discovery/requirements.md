# 要件定義書: Interest Discovery（興味関心発見）機能

## はじめに

本機能は、AI エージェント「rei」に会話履歴から興味関心トピックを自動発見・更新する機能を提供する。
会話履歴を定期的に分析して興味関心トピックを抽出し、Web 検索で最新情報を収集して日次ブリーフィングに活用する。

## 用語集

- **ConversationInterestService**: 会話履歴から興味関心トピック候補を発見するサービス。
- **ConversationHistoryService**: チャットメモリから会話履歴を取得するサービス。
- **InterestTopicExtractor**: 会話スニペットから興味関心トピック候補を抽出するインターフェース。
- **LlmInterestTopicExtractor**: LLM を使用して InterestTopicExtractor を実装するコンポーネント。
- **InterestUpdateService**: 興味関心トピックの更新情報を保存・取得するサービス。
- **InterestDiscoveryJob**: 定期的に興味関心トピックを発見・更新するスケジュールジョブ。
- **InterestNotificationJob**: 定期的に興味関心トピックの更新をコンソールに通知するスケジュールジョブ。
- **InterestTopicCandidate**: 抽出された興味関心トピック候補（トピック名・理由・検索クエリ・スコア）。
- **InterestUpdate**: 興味関心トピックの更新情報（トピック名・理由・検索クエリ・要約・ソース URL・作成日時）。
- **ConversationSnippet**: 会話履歴から取得したユーザーメッセージのスニペット。
- **過去クエリコンテキスト**: 検索クエリ生成時に LLM へ渡す、過去 N 日間に使用した検索クエリの一覧。重複を避けた多様な情報収集に使用する。
- **トピック更新頻度制限**: 同一トピック名に対して Web 検索を実行する最小間隔。同じトピックへの過剰な検索を防ぐ。
- **フォールバック候補抽出**: 通常の候補抽出で結果が得られない場合に、最大トピック数・最小スコアの条件を緩和して候補を抽出する仕組み。

## 要件

### 要件 1: 会話履歴からの興味関心トピック候補抽出

**ユーザーストーリー:** システムとして、会話履歴からユーザーの興味関心トピックを自動的に抽出したい。そうすることで、ユーザーが明示的に設定しなくても関心のある情報を収集できる。

#### 受け入れ基準

1. WHEN 興味関心発見処理が実行されたとき、THE ConversationInterestService SHALL 設定された日数以内のユーザーメッセージを会話履歴から取得する
2. THE LlmInterestTopicExtractor SHALL 取得した会話スニペットから LLM を使用して興味関心トピック候補を抽出する
3. THE ConversationInterestService SHALL 最小スコア以上のトピック候補のみを返す
4. THE ConversationInterestService SHALL 最大トピック数を上限としてトピック候補を返す

---

### 要件 2: 興味関心トピックの Web 検索と更新

**ユーザーストーリー:** システムとして、抽出された興味関心トピックについて Web 検索を実行して最新情報を収集したい。そうすることで、ユーザーの関心に沿った最新情報を提供できる。

#### 受け入れ基準

1. WHEN 興味関心発見ジョブが実行されたとき、THE InterestDiscoveryJob SHALL 興味関心トピック候補を取得して各トピックの Web 検索を実行する
2. WHEN 同一トピック名の更新情報が設定された頻度制限時間以内に既に存在するとき、THE InterestDiscoveryJob SHALL そのトピックの Web 検索をスキップする
3. WHEN Web 検索が成功したとき、THE InterestDiscoveryJob SHALL トピック名・理由・検索クエリ・要約・ソース URL を保存する
4. WHEN 興味関心機能が無効のとき、THE InterestDiscoveryJob SHALL 処理を実行しない
5. THE InterestDiscoveryJob SHALL トピックごとの更新頻度制限時間を `rei.interest.topic-update-interval-hours` プロパティで設定できる
6. WHEN 通常の候補抽出で 0 件だったとき、THE InterestDiscoveryJob SHALL 条件を緩和したフォールバック候補抽出を実行する
7. WHEN 通常の候補抽出で候補が得られたが Web 検索結果が 0 件だったとき、THE InterestDiscoveryJob SHALL フォールバック候補抽出を実行して再度 Web 検索を試みる

---

### 要件 2-B: フォールバック候補抽出

**ユーザーストーリー:** システムとして、通常の候補抽出で結果が得られない場合に条件を緩和して候補を抽出したい。そうすることで、会話が少ない状況でも興味関心情報を収集できる。

#### 受け入れ基準

1. THE ConversationInterestService SHALL 最大トピック数を通常の 2 倍・最小スコアを 0.2 引き下げた条件でフォールバック候補を抽出する `discoverFallbackCandidates()` を提供する
2. WHEN フォールバック候補抽出が実行されたとき、THE ConversationInterestService SHALL 緩和された条件でトピック候補を返す

---

### 要件 2-A: 過去クエリを考慮した検索クエリ生成

**ユーザーストーリー:** システムとして、過去に実行した検索クエリと重複しない新しい角度の検索クエリを生成したい。そうすることで、同じトピックでも毎回異なる切り口の情報を収集できる。

#### 受け入れ基準

1. WHEN 興味関心トピック候補の検索クエリを生成するとき、THE LlmInterestTopicExtractor SHALL 過去 N 日間に使用した検索クエリ一覧を LLM へのプロンプトに含める
2. THE LlmInterestTopicExtractor SHALL 過去クエリと意味的に重複しない新しい角度のクエリを生成するよう LLM に指示する
3. THE InterestUpdateService SHALL 過去クエリ一覧取得のために指定日数以内の検索クエリをすべて返すインターフェースを提供する
4. WHEN 同一検索クエリの更新情報が既に存在するとき、THE InterestDiscoveryJob SHALL そのクエリの Web 検索をスキップする

---

### 要件 3: 興味関心更新情報の保存と取得

**ユーザーストーリー:** システムとして、収集した興味関心更新情報を保存して後から取得したい。そうすることで、日次ブリーフィングに最新の興味関心情報を含められる。

#### 受け入れ基準

1. THE InterestUpdateService SHALL 興味関心更新情報をデータベースに保存する
2. THE InterestUpdateService SHALL 検索クエリを一意キーとして重複保存を防ぐ
3. WHEN 指定時間以内の更新情報を取得するとき、THE InterestUpdateService SHALL 作成日時降順で返す

---

### 要件 4: 興味関心更新の通知

**ユーザーストーリー:** システムとして、新しい興味関心更新情報をユーザーに通知したい。そうすることで、ユーザーが関心のある最新情報をリアルタイムに把握できる。

#### 受け入れ基準

1. WHEN 通知スケジュールが実行されたとき、THE InterestNotificationJob SHALL 直近の興味関心更新情報を通知する
2. WHEN 通知機能が無効のとき、THE InterestNotificationJob SHALL 通知を実行しない
3. THE SoundInterestNotifier SHALL 各更新情報についてトピック名と要約を結合したメッセージを SoundNotificationService を通じて音声通知する
4. WHEN 音声通知が実行されたとき、THE SoundInterestNotifier SHALL コンソールにも同じ内容を出力する
5. THE SoundInterestNotifier SHALL `@Primary` として登録され、`ConsoleInterestNotifier` より優先して使用される
