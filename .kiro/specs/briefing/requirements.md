# 要件定義書: Briefing 機能

## はじめに

本機能は、AI エージェント「rei」に日次ブリーフィング生成機能を提供する。
ユーザーが `briefing today` コマンドを実行すると、Google カレンダーの予定・未完了タスク・関連文書・新着フィード記事・興味関心トピックを統合し、AI が概要・注意点・次アクションを生成して表示する。

## 用語集

- **BriefingService**: 各種情報源からデータを収集し、日次ブリーフィングを生成するサービス。
- **BriefingNarrator**: ブリーフィングコンテキストを受け取り、AI による概要・注意点・次アクションを生成するインターフェース。
- **AiBriefingNarrator**: LLM を使用して BriefingNarrator を実装するコンポーネント。
- **BriefingContext**: ブリーフィング生成に必要な入力データ（日付・予定・タスク・関連文書）をまとめたデータクラス。
- **BriefingNarration**: AI が生成した概要・注意点・次アクションをまとめたデータクラス。
- **DailyBriefing**: 日次ブリーフィングの全情報（予定・タスク・関連文書・フィード記事・興味関心・AI 生成テキスト）をまとめたデータクラス。
- **BriefingTools**: AI エージェントからブリーフィングを呼び出すためのツール。
- **VectorStore**: ベクトル検索によって関連文書を検索するストア。
- **InterestUpdate**: 会話履歴から抽出された興味関心トピックと要約。

## 要件

### 要件 1: 今日の日次ブリーフィング生成

**ユーザーストーリー:** ユーザーとして、今日の日次ブリーフィングを取得したい。そうすることで、業務開始時に今日の予定・タスク・新着情報を一度に把握できる。

#### 受け入れ基準

1. WHEN ユーザーが `briefing today` コマンドを実行したとき、THE BriefingService SHALL 今日の日付に対するブリーフィングを生成して返す
2. THE BriefingService SHALL ブリーフィングに Google カレンダーの今日の予定を含める
3. THE BriefingService SHALL ブリーフィングに未完了タスクの一覧を含める
4. THE BriefingService SHALL ブリーフィングに期限切れタスクの一覧を含める
5. THE BriefingService SHALL ブリーフィングに予定・タスクに関連するベクトルストア文書を最大 3 件含める
6. THE BriefingService SHALL ブリーフィングに昨日 00:00 から翌日 00:00 までの新着フィード記事を含める
7. THE BriefingService SHALL ブリーフィングに直近 24 時間以内の興味関心トピック更新を含める

---

### 要件 2: AI によるブリーフィングナレーション生成

**ユーザーストーリー:** システムとして、収集した情報から AI が概要・注意点・次アクションを生成したい。そうすることで、ユーザーが情報を素早く把握できる。

#### 受け入れ基準

1. WHEN BriefingContext が提供されたとき、THE AiBriefingNarrator SHALL LLM に対してブリーフィング生成プロンプトを送信する
2. THE AiBriefingNarrator SHALL LLM のレスポンスから OVERVIEW・CAUTIONS・NEXT_ACTIONS の各セクションを解析する
3. WHEN LLM のレスポンスに OVERVIEW セクションが含まれるとき、THE AiBriefingNarrator SHALL 概要テキストを抽出する
4. WHEN LLM のレスポンスに CAUTIONS セクションが含まれるとき、THE AiBriefingNarrator SHALL 注意点の箇条書きリストを抽出する
5. WHEN LLM のレスポンスに NEXT_ACTIONS セクションが含まれるとき、THE AiBriefingNarrator SHALL 次アクションの箇条書きリストを抽出する
6. IF LLM のレスポンスが期待する形式でないとき、THEN THE AiBriefingNarrator SHALL レスポンス全体をフォールバックとして概要に設定する
7. THE AiBriefingNarrator SHALL 現在選択中のモデルを使用して LLM を呼び出す

---

### 要件 3: 予定・タスクなし時のフォールバック

**ユーザーストーリー:** システムとして、予定もタスクもない日にもブリーフィングを返したい。そうすることで、ユーザーが常に一貫した形式でブリーフィングを受け取れる。

#### 受け入れ基準

1. WHEN 今日の予定が空かつ未完了タスクが空のとき、THE BriefingService SHALL AI ナレーションを呼び出さずにデフォルトのブリーフィングを返す
2. WHEN フォールバックブリーフィングを返すとき、THE BriefingService SHALL 予定・タスクが存在しない旨の概要テキストを設定する
3. WHEN フォールバックブリーフィングを返すとき、THE BriefingService SHALL フィード記事と興味関心トピックは通常通り含める

---

### 要件 4: 関連文書の検索

**ユーザーストーリー:** システムとして、今日の予定とタスクに関連するベクトルストア文書を検索したい。そうすることで、ブリーフィングに文脈に沿った参考情報を含められる。

#### 受け入れ基準

1. WHEN 予定またはタスクが存在するとき、THE BriefingService SHALL 予定タイトルとタスクタイトルを結合したクエリでベクトルストアを検索する
2. THE BriefingService SHALL ベクトルストアから類似度の高い文書を最大 3 件取得する
3. WHEN 予定もタスクも存在しないとき、THE BriefingService SHALL ベクトルストア検索を実行しない
4. THE BriefingService SHALL 検索結果の各文書についてソース名とテキストスニペットを整形して返す

---

### 要件 5: ブリーフィングの表示

**ユーザーストーリー:** ユーザーとして、ブリーフィングの内容を整形されたテキストで確認したい。そうすることで、情報を読みやすい形式で把握できる。

#### 受け入れ基準

1. WHEN `briefing today` コマンドが実行されたとき、THE BriefingCommand SHALL 日付・概要・予定・未完了タスク・関連文書・新着記事・興味関心トピック・注意点・次アクションを順に表示する
2. WHEN 予定が存在しないとき、THE BriefingCommand SHALL 予定がない旨を表示する
3. WHEN 未完了タスクが存在しないとき、THE BriefingCommand SHALL 未完了タスクがない旨を表示する
4. WHEN 新着記事が存在しないとき、THE BriefingCommand SHALL 新着記事がない旨を表示する
5. WHEN 興味関心トピックが存在しないとき、THE BriefingCommand SHALL 該当なしを表示する

---

### 要件 6: AI エージェントからのブリーフィング呼び出し

**ユーザーストーリー:** AI エージェントとして、会話の中でブリーフィングを取得したい。そうすることで、ユーザーが自然言語でブリーフィングを要求できる。

#### 受け入れ基準

1. THE BriefingTools SHALL AI エージェントから今日の日次ブリーフィングを取得できるインターフェースを提供する
2. WHEN AI エージェントがブリーフィングツールを呼び出したとき、THE BriefingTools SHALL BriefingService の today() を呼び出して結果を返す
