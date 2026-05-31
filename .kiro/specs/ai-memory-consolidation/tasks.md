# Implementation Plan: AI 睡眠（記憶整理）機能

## Overview

本機能は rei AI エージェントに長期記憶能力を付与する。会話履歴・決定事項・タスク・ユーザー設定・プロジェクト文脈を LLM で抽出・分類し、専用 SQLite データベース（`.rei/memory-consolidation.db`）に永続化する。

実装は TDD（Red → Green → Refactor）サイクルで進める。各タスクはテストを先に書き、コンパイルエラーも Red として扱う。PBT タスク（`*` 付き）は jqwik を使用する。

## Task Dependency Graph

```
1 (jqwik) → 2 (models) → 3 (ReiPaths) → 4 (Properties) → 5 (DataSource)
                                                                    ↓
                                                              6 (MemoryService CRUD)
                                                                    ↓
                                                              7 (MemoryService FTS/Expiry)
                                                                    ↓
                                                              8 (checkpoint)
                                                                    ↓
9 (SensitiveInfoDetector) → 10 (PBT-17)
11 (ConflictResolver) → 12 (PBT-11,12)
7 → 13 (PBT-2,3,5,6,8,10,18,19)
                                                              14 (checkpoint)
                                                                    ↓
15 (MemoryExporter) → 16 (PBT-15)
17 (ConsolidatorService) → 18 (PBT-1,13,16)
                         → 19 (PBT-7)
                         → 20 (PBT-9)
                         → 21 (PBT-4)
                         → 22 (PBT-14)
                                                              23 (checkpoint)
                                                                    ↓
24 (list cmd) → 25 (search cmd) → 26 (forget cmd) → 27 (export cmd) → 28 (summarize cmd) → 29 (consolidate cmd)
                                                              30 (checkpoint)
                                                                    ↓
31 (application.yaml) → 32 (ChatCommand auto-trigger) → 33 (integration tests) → 34 (final checkpoint)
```

## Tasks

- [x] 1. pom.xml に jqwik 依存を追加する
  - [x] 1.1 `pom.xml` の `<dependencies>` に jqwik を追加する
    - `net.jqwik:jqwik:1.9.3` を `test` スコープで追加する
    - `./mvnw dependency:resolve` を実行して依存解決できることを確認する
  - _Requirements: テスト基盤_

- [x] 2. データモデルを実装する（MemoryType / MemoryScope / MemoryStatus / Memory）
  - [x] 2.1 **Red**: コンパイルエラーになるテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/model/MemoryModelTest.java` を新規作成する
    - `MemoryType` の全 7 値（USER_PREFERENCE / PROJECT_CONTEXT / DECISION / TASK / KNOWLEDGE / EPISODE_SUMMARY / TEMPORARY_CONTEXT）が存在することを検証するテストを書く
    - `MemoryScope` の全 5 値（SESSION / SHORT_TERM / PROJECT / LONG_TERM / PERMANENT）が存在することを検証するテストを書く
    - `MemoryStatus` の全 5 値（CANDIDATE / ACTIVE / DEPRECATED / ARCHIVED / DELETED）が存在することを検証するテストを書く
    - `Memory` record の全フィールド（id / content / type / scope / status / confidence / expiresAt / createdAt / updatedAt）が存在することを検証するテストを書く
  - [x] 2.2 **Green**: モデルクラスを実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/memory/model/MemoryType.java` を作成する
    - `src/main/java/dev/mikoto2000/rei/memory/model/MemoryScope.java` を作成する
    - `src/main/java/dev/mikoto2000/rei/memory/model/MemoryStatus.java` を作成する
    - `src/main/java/dev/mikoto2000/rei/memory/model/Memory.java` を record として作成する（全フィールドを含む）
    - テストが通ることを確認する
  - _Requirements: 2.3_

- [x] 3. ReiPaths に memoryConsolidationDbPath() を追加する（Red → Green）
  - [x] 3.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/core/datasource/ReiPathsTest.java` に `memoryConsolidationDbPath()` が `.rei/memory-consolidation.db` を返すことを検証するテストを追加する
    - この時点でコンパイルエラーになることを確認する
  - [x] 3.2 **Green**: `ReiPaths` にメソッドを追加してテストを通す
    - `memoryConsolidationDbPath()` と `memoryConsolidationDbPath(Path workDirectory)` を追加する
    - テストが通ることを確認する
  - _Requirements: 2.1, 2.7_

- [x] 4. MemoryProperties を実装する（Red → Green）
  - [x] 4.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/configuration/MemoryPropertiesTest.java` を新規作成する
    - デフォルト値（autoTriggerMessageThreshold=20 / autoTriggerContextPercent=80 / searchMaxResults=10 / searchMaxInjected=3 / summarizeMaxLength=2000 / conflictTimeoutSeconds=60 / expiry.shortTermDays=30 / expiry.longTermDays=365）を検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 4.2 **Green**: `MemoryProperties` を実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/memory/configuration/MemoryProperties.java` を `@ConfigurationProperties(prefix = "rei.memory")` record として作成する
    - `ExpiryDefaults` ネスト record を含める
    - テストが通ることを確認する
  - _Requirements: 7.1, 7.2_

- [x] 5. MemoryDataSourceConfiguration を実装する（Red → Green）
  - [x] 5.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/configuration/MemoryDataSourceConfigurationTest.java` を新規作成する
    - `memoryConsolidationDataSource()` が非 null の `DataSource` を返すことを検証するスモークテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 5.2 **Green**: `MemoryDataSourceConfiguration` を実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/memory/configuration/MemoryDataSourceConfiguration.java` を作成する
    - `@Bean @Qualifier("memoryConsolidationDataSource")` で `SQLiteDataSource` を返す Bean を実装する
    - `ReiPaths.memoryConsolidationDbPath()` を使用して DB パスを解決する
    - テストが通ることを確認する
  - _Requirements: 2.1, 2.8_

- [x] 6. MemoryService — スキーマ初期化と CRUD を実装する（Red → Green → Refactor）
  - [x] 6.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/service/MemoryServiceTest.java` を新規作成する（インメモリ SQLite を使用）
    - `initializeSchema()` 後に全 6 テーブル（memories / memory_tags / memory_sources / memory_relations / memory_summaries / memory_fts）が存在することを検証するテストを書く
    - `save(memory)` が非 null の id を持つ Memory を返すことを検証するテストを書く
    - `findById(id)` が保存した Memory を返すことを検証するテストを書く
    - `updateStatus(id, status)` 後に `findById()` のステータスが更新されることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 6.2 **Green**: `MemoryService` を実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/memory/service/MemoryService.java` を作成する
    - `initializeSchema()` で DDL を実行して全テーブルを作成する
    - `save()` / `findById()` / `listActive()` / `updateStatus()` / `saveRelation()` を実装する
    - `saveTags()` / `findTags()` / `saveSource()` / `saveSummary()` を実装する
    - テストが通ることを確認する
  - [x] 6.3 **Refactor**: SQL 文を定数に抽出する
  - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.8, 2.9_

- [x] 7. MemoryService — FTS 検索と有効期限チェックを実装する（Red → Green）
  - [x] 7.1 **Red**: 失敗するテストを書く
    - `MemoryServiceTest` に以下のテストを追加する
    - `search(query, limit)` が FTS インデックスを使用して一致する記憶を返すことを検証するテストを書く
    - `listActiveWithExpiryCheck()` が期限切れ記憶のステータスを `DEPRECATED` に更新して結果から除外することを検証するテストを書く
    - この時点でテストが失敗することを確認する
  - [x] 7.2 **Green**: `search()` と `listActiveWithExpiryCheck()` を実装してテストを通す
    - `search()` で `memory_fts` テーブルを使用した FTS5 クエリを実装する
    - `listActiveWithExpiryCheck()` で `expires_at < now()` の記憶を `DEPRECATED` に更新してから `ACTIVE` 記憶を返す
    - テストが通ることを確認する
  - _Requirements: 2.6, 4.1, 11.2_

- [x] 8. チェックポイント — MemoryService のテストがすべて通ることを確認する
  - `./mvnw test "-Dtest=MemoryService*"` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

- [x] 9. SensitiveInfoDetector を実装する（Red → Green）
  - [x] 9.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/util/SensitiveInfoDetectorTest.java` を新規作成する
    - メールアドレス形式の文字列で `containsSensitiveInfo()` が `true` を返すことを検証するテストを書く
    - 電話番号形式の文字列で `containsSensitiveInfo()` が `true` を返すことを検証するテストを書く
    - `password=xxx` 形式の文字列で `containsSensitiveInfo()` が `true` を返すことを検証するテストを書く
    - `-----BEGIN CERTIFICATE-----` 形式の文字列で `containsSensitiveInfo()` が `true` を返すことを検証するテストを書く
    - 機密情報を含まない通常テキストで `containsSensitiveInfo()` が `false` を返すことを検証するテストを書く
    - `detectPatterns()` が検出されたパターン名のリストを返すことを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 9.2 **Green**: `SensitiveInfoDetector` を実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/memory/util/SensitiveInfoDetector.java` を作成する
    - EMAIL / PHONE_JP / PHONE_INTL / SECRET_KEY / PEM の 5 パターンを正規表現で実装する
    - `containsSensitiveInfo()` と `detectPatterns()` を実装する
    - テストが通ることを確認する
  - _Requirements: 10.1_

- [x] 10. SensitiveInfoDetector のプロパティテストを追加する
  - [x]* 10.1 **Property 17: 機密情報検出と保護**
    - `src/test/java/dev/mikoto2000/rei/memory/util/SensitiveInfoDetectorPropertyTest.java` を新規作成する
    - jqwik の `@Property(tries = 100)` を使用する
    - メールアドレス・電話番号・パスワード系キーと値のペア・PEM 文字列のいずれかを含む content について `containsSensitiveInfo()` が `true` を返すことを検証する
    - 機密情報パターンを含まない任意の文字列で `containsSensitiveInfo()` が `false` を返すことを検証する
    - **Validates: Requirements 10.1, 10.2, 10.4**
    - タグ: `// Feature: ai-memory-consolidation, Property 17: 機密情報検出と保護`
  - _Requirements: 10.1, 10.2, 10.4_

- [x] 11. MemoryConflictResolver を実装する（Red → Green）
  - [x] 11.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/service/MemoryConflictResolverTest.java` を新規作成する
    - 類似度スコア ≥ 0.95 のとき `ConflictResult.type` が `DUPLICATE` になることを検証するテストを書く
    - 0.8 ≤ 類似度スコア < 0.95 のとき `ConflictResult.type` が `CONTRADICTION` になることを検証するテストを書く
    - 類似度スコア < 0.8 のとき `ConflictResult.type` が `NONE` になることを検証するテストを書く
    - 既知のテキストペアに対して `computeSimilarity()` が期待値に近いスコアを返すことを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 11.2 **Green**: `MemoryConflictResolver` を実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/memory/service/MemoryConflictResolver.java` を作成する
    - `ConflictResult` record（type / conflicting / similarityScore）を作成する
    - `ConflictType` enum（NONE / DUPLICATE / CONTRADICTION）を作成する
    - Jaccard 類似度ベースの `computeSimilarity()` を実装する
    - `check()` で類似度スコアに基づいて `ConflictResult` を返す
    - テストが通ることを確認する
  - _Requirements: 6.1, 6.4_

- [x] 12. MemoryConflictResolver のプロパティテストを追加する
  - [x]* 12.1 **Property 11: 類似度スコアに基づく矛盾・重複判定**
    - `src/test/java/dev/mikoto2000/rei/memory/service/MemoryConflictResolverPropertyTest.java` を新規作成する
    - jqwik の `@Property(tries = 100)` を使用する
    - ランダムな類似度スコア（0.0〜1.0）について、スコア ≥ 0.95 のとき `DUPLICATE`、0.8 ≤ スコア < 0.95 のとき `CONTRADICTION`、スコア < 0.8 のとき `NONE` と判定されることを検証する
    - **Validates: Requirements 6.1**
    - タグ: `// Feature: ai-memory-consolidation, Property 11: 類似度スコアに基づく矛盾・重複判定`
  - [x]* 12.2 **Property 12: deprecated 更新の不変条件**
    - `MemoryConflictResolverPropertyTest` に追加する（インメモリ SQLite 使用）
    - ランダムな `ACTIVE` 記憶について `updateStatus(id, DEPRECATED)` 後にステータスが `DEPRECATED` になり物理レコードが残存することを検証する
    - **Validates: Requirements 6.4**
    - タグ: `// Feature: ai-memory-consolidation, Property 12: deprecated 更新の不変条件`
  - _Requirements: 6.1, 6.4_

- [x] 13. MemoryService のプロパティテストを追加する
  - [x]* 13.1 **Property 2: 承認記憶の保存ラウンドトリップ**
    - `src/test/java/dev/mikoto2000/rei/memory/service/MemoryServicePropertyTest.java` を新規作成する
    - jqwik の `@Property(tries = 100)` を使用する（インメモリ SQLite）
    - ランダムな Memory オブジェクト（全フィールド）について `save()` 後に `findById()` で取得したとき content / type / scope / confidence の全フィールドが一致し、ステータスが `ACTIVE` であることを検証する
    - **Validates: Requirements 1.5, 2.3, 2.4, 2.5**
    - タグ: `// Feature: ai-memory-consolidation, Property 2: 承認記憶の保存ラウンドトリップ`
  - [x]* 13.2 **Property 3: 却下記憶の非保存**
    - ランダムな候補リストについて却下処理後に `findById()` でレコードが存在しないことを検証する
    - **Validates: Requirements 1.6, 9.5**
    - タグ: `// Feature: ai-memory-consolidation, Property 3: 却下記憶の非保存`
  - [x]* 13.3 **Property 5: FTS 検索の一貫性**
    - ランダムな content を持つ記憶を保存した後、その content に含まれる単語で FTS 検索を実行したとき、その記憶が検索結果に含まれることを検証する
    - **Validates: Requirements 2.6, 4.1**
    - タグ: `// Feature: ai-memory-consolidation, Property 5: FTS 検索の一貫性`
  - [x]* 13.4 **Property 6: 一覧取得の順序とフィルタリング**
    - `ACTIVE` / `DELETED` / `DEPRECATED` が混在する記憶セットについて `listActive()` の結果が `ACTIVE` ステータスの記憶のみを含み `created_at` の降順で並んでいることを検証する
    - **Validates: Requirements 3.1**
    - タグ: `// Feature: ai-memory-consolidation, Property 6: 一覧取得の順序とフィルタリング`
  - [x]* 13.5 **Property 8: 検索結果の件数上限とフィルタリング**
    - 10 件超の `ACTIVE` 記憶を含む記憶セットについて `search()` の結果が最大 10 件であり、すべて `ACTIVE` ステータスであることを検証する
    - **Validates: Requirements 4.1, 4.2**
    - タグ: `// Feature: ai-memory-consolidation, Property 8: 検索結果の件数上限とフィルタリング`
  - [x]* 13.6 **Property 10: 論理削除の不変条件**
    - ランダムな `ACTIVE` 記憶について `updateStatus(id, DELETED)` 後にステータスが `DELETED` になり物理レコードが残存し、`listActive()` および `search()` の結果に含まれないことを検証する
    - **Validates: Requirements 5.1**
    - タグ: `// Feature: ai-memory-consolidation, Property 10: 論理削除の不変条件`
  - [x]* 13.7 **Property 18: スコープ別デフォルト有効期限の設定**
    - ランダムな `MemoryScope` 値で記憶を保存したとき `expires_at` フィールドがスコープのデフォルト有効期限と一致することを検証する（SHORT_TERM: +30 日、LONG_TERM: +365 日、PERMANENT: null）
    - **Validates: Requirements 11.1, 11.3**
    - タグ: `// Feature: ai-memory-consolidation, Property 18: スコープ別デフォルト有効期限の設定`
  - [x]* 13.8 **Property 19: 有効期限切れ記憶の自動 deprecated 化**
    - `expires_at` が現在日時より過去の `ACTIVE` 記憶を含む DB から `listActiveWithExpiryCheck()` を呼び出したとき、期限切れ記憶のステータスが `DEPRECATED` に更新され結果リストに含まれないことを検証する
    - **Validates: Requirements 11.2**
    - タグ: `// Feature: ai-memory-consolidation, Property 19: 有効期限切れ記憶の自動 deprecated 化`
  - _Requirements: 1.5, 1.6, 2.3, 2.4, 2.5, 2.6, 3.1, 4.1, 4.2, 5.1, 9.5, 11.1, 11.2, 11.3_

- [x] 14. チェックポイント — MemoryService / MemoryConflictResolver / SensitiveInfoDetector のテストがすべて通ることを確認する
  - `./mvnw test "-Dtest=Memory*,SensitiveInfo*"` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

- [x] 15. MemoryExporter を実装する（Red → Green）
  - [x] 15.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/service/MemoryExporterTest.java` を新規作成する（一時ディレクトリ使用）
    - `export()` 後に `latest.md` が生成されることを検証するテストを書く
    - `export()` 後に日付付き `.md` ファイルが生成されることを検証するテストを書く
    - `export()` 後に日付付き `.jsonl` ファイルが生成されることを検証するテストを書く
    - `ExportResult.count` が `ACTIVE` 記憶の件数と一致することを検証するテストを書く
    - `ACTIVE` 記憶が存在しないとき `count` が 0 であることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 15.2 **Green**: `MemoryExporter` を実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/memory/service/MemoryExporter.java` を作成する
    - `ExportResult` record（latestMd / datedMd / datedJsonl / count）を作成する
    - `export(Path exportDir)` で Markdown と JSONL を出力する
    - Markdown は各記憶を `## [type] content` 形式で出力する
    - JSONL は各記憶を 1 行の JSON オブジェクトとして出力する
    - テストが通ることを確認する
  - _Requirements: 8.1, 8.2, 8.3, 8.4_

- [x] 16. MemoryExporter のプロパティテストを追加する
  - [x]* 16.1 **Property 15: エクスポート出力の完全性と正確性**
    - `src/test/java/dev/mikoto2000/rei/memory/service/MemoryExporterPropertyTest.java` を新規作成する
    - jqwik の `@Property(tries = 100)` を使用する（一時ディレクトリ使用）
    - ランダムな `ACTIVE` 記憶セットについてエクスポート後に生成された `latest.md` および日付付き `.md` ファイルが全 `ACTIVE` 記憶の content を含み、`.jsonl` ファイルが各行が有効な JSON オブジェクトである有効な JSON Lines 形式であり、報告された出力件数が実際の `ACTIVE` 記憶数と一致することを検証する
    - **Validates: Requirements 8.1, 8.2, 8.3, 8.4**
    - タグ: `// Feature: ai-memory-consolidation, Property 15: エクスポート出力の完全性と正確性`
  - _Requirements: 8.1, 8.2, 8.3, 8.4_

- [x] 17. MemoryConsolidatorService を実装する（Red → Green）
  - [x] 17.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/service/MemoryConsolidatorServiceTest.java` を新規作成する（モック ChatClient 使用）
    - `extractCandidates()` が返す各 Memory のステータスが `CANDIDATE`、信頼度が 0.0〜1.0、type が有効な `MemoryType`、scope が有効な `MemoryScope` であることを検証するテストを書く
    - `summarize()` が返す文字列が 2000 文字以内であることを検証するテストを書く
    - `shouldSuggestConsolidation(messageCount, contextLength, contextLimit)` がメッセージ数が閾値を超えるとき `true` を返すことを検証するテストを書く
    - `shouldSuggestConsolidation()` がコンテキスト長が上限の 80% を超えるとき `true` を返すことを検証するテストを書く
    - `shouldSuggestConsolidation()` が両条件を満たさないとき `false` を返すことを検証するテストを書く
    - 会話履歴が空のとき `extractCandidates()` が空リストを返すことを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 17.2 **Green**: `MemoryConsolidatorService` を実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/memory/service/MemoryConsolidatorService.java` を作成する
    - `extractCandidates()` で LLM に重要情報抽出プロンプトを送信し、JSON レスポンスを `List<Memory>` に変換する
    - `summarize()` で LLM に要約プロンプトを送信し、2000 文字以内に切り詰めて返す
    - `shouldSuggestConsolidation()` でメッセージ数と `autoTriggerMessageThreshold` を比較し、コンテキスト長と `autoTriggerContextPercent` を比較する
    - テストが通ることを確認する
  - _Requirements: 1.1, 1.2, 1.3, 7.1, 7.2, 9.1_

- [x] 18. MemoryConsolidatorService のプロパティテストを追加する
  - [x]* 18.1 **Property 1: 保存候補の不変条件**
    - `src/test/java/dev/mikoto2000/rei/memory/service/MemoryConsolidatorServicePropertyTest.java` を新規作成する
    - jqwik の `@Property(tries = 100)` を使用する（モック ChatClient）
    - ランダムな会話メッセージリストから生成された保存候補リストの各要素について、ステータスが `CANDIDATE`、信頼度が 0.0〜1.0 の範囲内、type が `MemoryType` の有効値、scope が `MemoryScope` の有効値であることを検証する
    - **Validates: Requirements 1.2, 1.3**
    - タグ: `// Feature: ai-memory-consolidation, Property 1: 保存候補の不変条件`
  - [x]* 18.2 **Property 13: 自動トリガー判定の閾値**
    - ランダムなメッセージ数と設定閾値の組み合わせについて、メッセージ数が閾値を超えるとき `shouldSuggestConsolidation()` が `true` を返し、閾値以下のとき `false` を返すことを検証する
    - コンテキスト長が上限の 80% を超えるときも同様に `true` を返すことを検証する
    - **Validates: Requirements 7.1, 7.2**
    - タグ: `// Feature: ai-memory-consolidation, Property 13: 自動トリガー判定の閾値`
  - [x]* 18.3 **Property 16: 要約の長さ制約**
    - ランダムな会話履歴について `summarize()` が生成する要約テキストが 2000 文字以内であることを検証する（モック ChatClient は 2000 文字超の文字列を返す）
    - **Validates: Requirements 9.1**
    - タグ: `// Feature: ai-memory-consolidation, Property 16: 要約の長さ制約`
  - _Requirements: 1.2, 1.3, 7.1, 7.2, 9.1_

- [x] 19. コンテンツプレビューのプロパティテストを追加する
  - [x]* 19.1 **Property 7: コンテンツプレビューの長さ制約**
    - `src/test/java/dev/mikoto2000/rei/memory/service/ContentPreviewPropertyTest.java` を新規作成する
    - jqwik の `@Property(tries = 100)` を使用する
    - ランダムな長さの文字列（0〜500 文字）についてプレビュー生成関数の結果が 100 文字以内であることを検証する
    - 元の content が 100 文字以内の場合はそのまま返し、超える場合は末尾に「…」を付与することを検証する
    - **Validates: Requirements 3.2**
    - タグ: `// Feature: ai-memory-consolidation, Property 7: コンテンツプレビューの長さ制約`
  - _Requirements: 3.2_

- [x] 20. プロンプト注入のプロパティテストを追加する
  - [x]* 20.1 **Property 9: プロンプト注入の件数上限**
    - `src/test/java/dev/mikoto2000/rei/memory/service/PromptInjectionPropertyTest.java` を新規作成する
    - jqwik の `@Property(tries = 100)` を使用する
    - ランダムな件数の記憶リスト（0〜20 件）についてプロンプトへ注入される記憶が最大 3 件であることを検証する
    - **Validates: Requirements 4.4**
    - タグ: `// Feature: ai-memory-consolidation, Property 9: プロンプト注入の件数上限`
  - _Requirements: 4.4_

- [x] 21. 整理結果レポートのプロパティテストを追加する
  - [x]* 21.1 **Property 4: 整理結果レポートの件数一致**
    - `src/test/java/dev/mikoto2000/rei/memory/service/ConsolidationReportPropertyTest.java` を新規作成する
    - jqwik の `@Property(tries = 100)` を使用する
    - ランダムな承認セットと却下セットの組み合わせについて、整理結果レポートに含まれる保存件数・却下件数の合計が元の候補件数と一致することを検証する
    - **Validates: Requirements 1.7**
    - タグ: `// Feature: ai-memory-consolidation, Property 4: 整理結果レポートの件数一致`
  - _Requirements: 1.7_

- [x] 22. 自動トリガー非保存のプロパティテストを追加する
  - [x]* 22.1 **Property 14: 自動トリガー時の非保存**
    - `src/test/java/dev/mikoto2000/rei/memory/service/AutoTriggerPropertyTest.java` を新規作成する
    - jqwik の `@Property(tries = 100)` を使用する
    - ランダムなトリガー条件について自動トリガー発動後、ユーザーが明示的に承認操作を行わない限り `MemoryService.save()` が呼び出されないことを検証する（モック MemoryService で `verify(memoryService, never()).save(any())` を使用）
    - **Validates: Requirements 7.5**
    - タグ: `// Feature: ai-memory-consolidation, Property 14: 自動トリガー時の非保存`
  - _Requirements: 7.5_

- [x] 23. チェックポイント — サービス層のテストがすべて通ることを確認する
  - `./mvnw test "-Dtest=Memory*,SensitiveInfo*,ContentPreview*,PromptInjection*,ConsolidationReport*,AutoTrigger*"` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

- [x] 24. MemoryCommand — `list` サブコマンドを実装する（Red → Green）
  - [x] 24.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/command/MemoryListCommandTest.java` を新規作成する
    - `ACTIVE` 記憶が存在するとき一覧が表示されることを検証するテストを書く
    - `ACTIVE` 記憶が存在しないとき「保存済みの記憶はありません」が表示されることを検証するテストを書く
    - 内容プレビューが 100 文字以内であることを検証するテストを書く（101 文字の content を持つ記憶を使用）
    - この時点でコンパイルエラーになることを確認する
  - [x] 24.2 **Green**: `MemoryCommand` と `ListCommand` を実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/memory/command/MemoryCommand.java` を作成する（空のコンテナ）
    - `ListCommand` static inner class を実装する
    - `listActiveWithExpiryCheck()` を呼び出して結果を表示する
    - 内容プレビューを 100 文字以内に切り詰めて「…」を付与するヘルパーメソッドを実装する
    - テストが通ることを確認する
  - _Requirements: 3.1, 3.2, 3.3_

- [x] 25. MemoryCommand — `search` サブコマンドを実装する（Red → Green）
  - [x] 25.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/command/MemorySearchCommandTest.java` を新規作成する
    - 有効なクエリで検索結果が表示されることを検証するテストを書く
    - 検索結果が存在しないとき「該当する記憶が見つかりませんでした」が表示されることを検証するテストを書く
    - 空クエリのとき「検索クエリを入力してください」が表示されることを検証するテストを書く
    - 200 文字超のクエリのとき「検索クエリは 200 文字以内で入力してください」が表示されることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 25.2 **Green**: `SearchCommand` を実装してテストを通す
    - `SearchCommand` static inner class を `MemoryCommand` に追加する
    - クエリのバリデーション（空・200 文字超）を実装する
    - `MemoryService.search()` を呼び出して結果を表示する
    - テストが通ることを確認する
  - _Requirements: 4.1, 4.2, 4.5, 4.6, 4.7_

- [x] 26. MemoryCommand — `forget` サブコマンドを実装する（Red → Green）
  - [x] 26.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/command/MemoryForgetCommandTest.java` を新規作成する
    - 有効な ID で論理削除が実行され「記憶 `<id>` を削除しました」が表示されることを検証するテストを書く
    - 存在しない ID のとき「指定された ID の記憶が見つかりません」が表示されることを検証するテストを書く
    - すでに `DELETED` の記憶のとき「指定された記憶はすでに削除済みです」が表示されることを検証するテストを書く
    - 空 ID のとき「有効な記憶 ID を指定してください」が表示されることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 26.2 **Green**: `ForgetCommand` を実装してテストを通す
    - `ForgetCommand` static inner class を `MemoryCommand` に追加する
    - ID バリデーションを実装する
    - `MemoryService.updateStatus(id, DELETED)` を呼び出す
    - テストが通ることを確認する
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [x] 27. MemoryCommand — `export` サブコマンドを実装する（Red → Green）
  - [x] 27.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/command/MemoryExportCommandTest.java` を新規作成する（一時ディレクトリ使用）
    - エクスポート完了後に出力ファイルパスと件数が表示されることを検証するテストを書く
    - `ACTIVE` 記憶が存在しないとき「エクスポート対象の記憶がありません」が表示されることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 27.2 **Green**: `ExportCommand` を実装してテストを通す
    - `ExportCommand` static inner class を `MemoryCommand` に追加する
    - `MemoryExporter.export()` を呼び出して結果を表示する
    - テストが通ることを確認する
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6_

- [x] 28. MemoryCommand — `summarize` サブコマンドを実装する（Red → Green）
  - [x] 28.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/command/MemorySummarizeCommandTest.java` を新規作成する（モック ChatClient 使用）
    - 要約が生成されてユーザーに提示されることを検証するテストを書く
    - ユーザーが保存を承認したとき `MemoryService.save()` が呼ばれることを検証するテストを書く
    - ユーザーが保存を拒否したとき `MemoryService.save()` が呼ばれないことを検証するテストを書く
    - 会話履歴が空のとき「要約対象の会話履歴がありません」が表示されることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 28.2 **Green**: `SummarizeCommand` を実装してテストを通す
    - `SummarizeCommand` static inner class を `MemoryCommand` に追加する
    - `MemoryConsolidatorService.summarize()` を呼び出して要約を表示する
    - ユーザー確認後に `MemoryService.save()` を呼び出す
    - テストが通ることを確認する
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6_

- [x] 29. MemoryCommand — `consolidate` サブコマンドを実装する（Red → Green）
  - [x] 29.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/command/MemoryConsolidateCommandTest.java` を新規作成する（モック ChatClient 使用）
    - 保存候補が提示されてユーザーが承認したとき `MemoryService.save()` が呼ばれることを検証するテストを書く
    - ユーザーが却下したとき `MemoryService.save()` が呼ばれないことを検証するテストを書く
    - 整理結果レポート（保存件数・却下件数・種別内訳）が表示されることを検証するテストを書く
    - 会話履歴が空のとき「整理対象の会話履歴がありません」が表示されることを検証するテストを書く
    - 機密情報を含む候補に警告ラベルが付与されることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 29.2 **Green**: `ConsolidateCommand` を実装してテストを通す
    - `ConsolidateCommand` static inner class を `MemoryCommand` に追加する
    - `MemoryConsolidatorService.extractCandidates()` を呼び出して候補を取得する
    - `SensitiveInfoDetector.containsSensitiveInfo()` で機密情報チェックを行い警告ラベルを付与する
    - ユーザーへの承認/却下確認を実装する
    - `MemoryConflictResolver.check()` で矛盾・重複チェックを行う
    - 承認された記憶を `MemoryService.save()` で保存する
    - 整理結果レポートを表示する
    - テストが通ることを確認する
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 10.1, 10.2, 10.3, 10.4_

- [x] 30. チェックポイント — コマンド層のテストがすべて通ることを確認する
  - `./mvnw test "-Dtest=Memory*Command*"` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

- [x] 31. application.yaml に rei.memory 設定を追加する
  - [x] 31.1 `src/main/resources/application.yaml` に `rei.memory` セクションを追加する
    - `enabled: ${REI_MEMORY_ENABLED:true}` を追加する
    - `auto-trigger-message-threshold: ${REI_MEMORY_AUTO_TRIGGER_MSG_THRESHOLD:20}` を追加する
    - `auto-trigger-context-percent: ${REI_MEMORY_AUTO_TRIGGER_CTX_PERCENT:80}` を追加する
    - `search-max-results: ${REI_MEMORY_SEARCH_MAX_RESULTS:10}` を追加する
    - `search-max-injected: ${REI_MEMORY_SEARCH_MAX_INJECTED:3}` を追加する
    - `summarize-max-length: ${REI_MEMORY_SUMMARIZE_MAX_LENGTH:2000}` を追加する
    - `conflict-timeout-seconds: ${REI_MEMORY_CONFLICT_TIMEOUT_SECONDS:60}` を追加する
    - `expiry.short-term-days: ${REI_MEMORY_EXPIRY_SHORT_TERM_DAYS:30}` を追加する
    - `expiry.long-term-days: ${REI_MEMORY_EXPIRY_LONG_TERM_DAYS:365}` を追加する
  - _Requirements: 7.1, 7.2_

- [x] 32. ChatCommand に自動トリガー連携を追加する（Red → Green）
  - [x] 32.1 **Red**: 失敗するテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/command/MemoryAutoTriggerTest.java` を新規作成する
    - `ChatCommand.run()` 完了後にメッセージ数が閾値を超えているとき整理提案メッセージが表示されることを検証するテストを書く
    - `rei.memory.enabled=false` のとき整理提案が表示されないことを検証するテストを書く
    - この時点でテストが失敗することを確認する
  - [x] 32.2 **Green**: `ChatCommand` に `MemoryConsolidatorService` の任意依存を追加してテストを通す
    - `ChatCommand` に `Optional<MemoryConsolidatorService>` フィールドを追加する（`@Autowired(required=false)` または `Optional` 注入）
    - `run()` の応答受信完了後に `shouldSuggestConsolidation()` を呼び出す
    - 提案が必要なとき `[memory] 記憶整理を実行することをお勧めします。/memory consolidate を実行してください。` を表示する
    - 既存の `ChatCommandTest` と `ChatCommandCancellationTest` が引き続き通ることを確認する
    - テストが通ることを確認する
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

- [x] 33. インテグレーションテストを追加する
  - [x] 33.1 `MemoryDataSourceConfiguration` のスモークテストを書く
    - `src/test/java/dev/mikoto2000/rei/memory/configuration/MemoryDataSourceIntegrationTest.java` を新規作成する
    - DB ファイルが指定パスに作成されることを検証するテストを書く（一時ディレクトリ使用）
  - [x] 33.2 `MemoryService.initializeSchema()` のスモークテストを書く
    - 全 6 テーブル（memories / memory_tags / memory_sources / memory_relations / memory_summaries / memory_fts）が存在することを検証するテストを書く
  - [x] 33.3 エクスポートファイルの実際の書き込みを確認するテストを書く
    - 一時ディレクトリを使用して `MemoryExporter.export()` が実際にファイルを書き込むことを検証するテストを書く
  - _Requirements: 2.1, 2.2, 2.8, 8.2, 8.3_

- [x] 34. 最終チェックポイント — すべてのテストが通ることを確認する
  - `./mvnw test` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

## Notes

- **TDD の鉄則**: テストを書く前に実装を書かない。Red を確認してから Green に進む
- **最小実装**: Green フェーズでは「動く最小限」を書く。きれいにするのは Refactor フェーズ
- **小さいステップ**: 1 つのサブタスクで変更するファイルは原則 1〜2 ファイルに留める
- **インメモリ SQLite**: サービス層のテストでは `jdbc:sqlite::memory:` を使用してテスト間の独立性を保つ
- **モック ChatClient**: `MemoryConsolidatorService` のテストでは `ChatClient` を Mockito でモック化し、LLM 呼び出しを差し替える
- **jqwik の使用**: PBT タスク（`*` 付き）では jqwik の `@Property(tries = 100)` + `@ForAll` を使用する。タグコメントは `// Feature: ai-memory-consolidation, Property N: <property_text>` 形式で付与する
- **ChatCommand への影響**: `MemoryConsolidatorService` は `Optional` 注入とし、`rei.memory.enabled=false` のとき Bean が生成されなくても既存動作に影響しないようにする
- **FTS5 の注意**: SQLite の FTS5 は `sqlite-jdbc` に含まれているが、インメモリ DB でも動作することを確認する
- **DB パス**: 設計書では `.rei/memory-consolidation.db` を使用する（requirements.md の `./memory/memory.db` は旧仕様）
- **プロパティテストのタグ名**: タグコメントは `// Feature: ai-memory-consolidation, Property N: ...` 形式で統一する
