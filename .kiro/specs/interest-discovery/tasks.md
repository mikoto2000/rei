# 実装計画: Interest Discovery（興味関心発見）機能 D案

## 概要

D案（A案＋C案）の実装計画。以下の 2 つの改善を組み合わせる。

- **A案（過去クエリコンテキスト）**: トピック候補の検索クエリ生成時に、過去 N 日間の検索クエリ一覧を LLM に渡し、重複しない新しい角度のクエリを生成させる
- **C案（トピック更新頻度制限）**: 同一トピック名の更新情報が `rei.interest.topic-update-interval-hours` 時間以内に存在する場合はスキップする

## TDD の進め方

各タスクは **Red → Green → Refactor** サイクルで進める。

1. **Red**: 失敗するテストを先に書く。コンパイルエラーも Red として扱う
2. **Green**: テストが通る最小限の実装を書く。きれいさより動くことを優先する
3. **Refactor**: テストが通ったまま、重複排除・命名改善・設計整理を行う

> テストを書く前に実装を書かない。実装を書く前にテストを書く。

---

## タスク

- [x] 1. pom.xml に jqwik を追加する
  - `net.jqwik:jqwik` を `test` スコープで追加する（バージョン: `1.9.3`）
  - 追加後に `./mvnw test-compile` が通ることを確認する
  - _Requirements: テスト要件_

- [x] 2. InterestProperties に新プロパティを追加する（Red → Green → Refactor）
  - [x] 2.1 **Red**: デフォルト値を検証するテストを書く
    - `topicUpdateIntervalHours` のデフォルトが `24` であることを検証するテストを書く
    - `pastQueryLookbackDays` のデフォルトが `7` であることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 2.2 **Green**: フィールドと getter/setter を追加してテストを通す
    - `topicUpdateIntervalHours`（デフォルト 24）フィールドと getter/setter を追加する
    - `pastQueryLookbackDays`（デフォルト 7）フィールドと getter/setter を追加する
    - テストが通ることを確認する
  - [x] 2.3 **Refactor**: Lombok の `@Getter`/`@Setter` で冗長な記述がないか確認する
  - _Requirements: 2.5, 2-A.3_

- [x] 3. InterestUpdateService — `existsByTopicWithinHours` を TDD で追加する
  - [x] 3.1 **Red**: 失敗するテストを書く
    - インメモリ SQLite を使い、制限時間内に同一トピックのレコードがある場合に `true` を返すことを検証するテストを書く
    - 制限時間外のレコードしかない場合に `false` を返すことを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 3.2 **Green**: 最小限の実装でテストを通す
    - SQL: `SELECT COUNT(*) FROM interest_updates WHERE topic = ? AND created_at >= ?`
    - テストが通ることを確認する
  - [x] 3.3 **Refactor**: SQL クエリや変数名を整理する
  - _Requirements: 2.2, 2.5_

- [x] 4. InterestUpdateService — `listRecentSearchQueries` を TDD で追加する
  - [x] 4.1 **Red**: 失敗するテストを書く
    - N 日以内のクエリのみが返ることを検証するテストを書く
    - N 日超のクエリが含まれないことを検証するテストを書く
    - 重複クエリが 1 件に集約されることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 4.2 **Green**: 最小限の実装でテストを通す
    - SQL: `SELECT DISTINCT search_query FROM interest_updates WHERE created_at >= ? ORDER BY created_at DESC`
    - テストが通ることを確認する
  - [x] 4.3 **Refactor**: `existsByTopicWithinHours` と共通する日時計算ロジックがあれば抽出する
  - _Requirements: 2-A.3_

- [x] 5. InterestUpdateService — プロパティテストを追加する
  - [x] 5.1 **Property 2: 過去クエリ一覧は指定日数以内のものだけを返す**
    - `@Property` で任意の日数 N（1〜30）と、N 日以内・N 日超の混在データに対して、N 日以内のクエリのみが返ることを検証する
    - **Validates: Requirements 2-A.3**
  - [x] 5.2 **Property 3（部分）: `existsByTopicWithinHours` の境界値**
    - `@Property` で任意のトピック名と時間範囲に対して、制限内レコードが存在する場合に `true`、存在しない場合に `false` を返すことを検証する
    - **Validates: Requirements 2.2**

- [x] 6. InterestTopicExtractor インターフェースに `pastQueries` 付きデフォルトメソッドを追加する（Red → Green）
  - [x] 6.1 **Red**: `extract(snippets, maxTopics, pastQueries)` を呼び出すテストを書く
    - `pastQueries` を渡した場合に既存の `extract(snippets, maxTopics)` と同じ結果を返すことを検証する
    - この時点でコンパイルエラーになることを確認する
  - [x] 6.2 **Green**: `default` メソッドとして追加し、既存の `extract(snippets, maxTopics)` に委譲する
    - テストが通ることを確認する（後方互換性の維持）
  - _Requirements: 2-A.1_

- [x] 7. LlmInterestTopicExtractor — `buildPrompt` の過去クエリセクションを TDD で追加する
  - [x] 7.1 **Red**: 失敗するテストを書く
    - `pastQueries` が空でない場合、プロンプトに各クエリ文字列が含まれることを検証するテストを書く
    - `pastQueries` が空の場合、過去クエリセクションがプロンプトに含まれないことを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 7.2 **Green**: `buildPrompt(snippets, maxTopics, pastQueries)` シグネチャに変更し、テストを通す
    - `pastQueries` が空でない場合のみ「過去に使用した検索クエリ（これらと重複・類似しない新しい角度のクエリを生成すること）」セクションを追加する
    - テストが通ることを確認する
  - [x] 7.3 **Refactor**: テキストブロックの整形・インデントを整理する
  - _Requirements: 2-A.1, 2-A.2_

- [x] 8. LlmInterestTopicExtractor — プロパティテストを追加する
  - [x] 8.1 **Property 1: 過去クエリがプロンプトに含まれる**
    - `@Property` で任意の過去クエリリストに対して、`buildPrompt()` が生成するプロンプト文字列にすべてのクエリが含まれることを検証する
    - **Validates: Requirements 2-A.1**

- [x] 9. チェックポイント — ここまでのテストがすべて通ることを確認する
  - `./mvnw test` を実行してすべてのテストが通ることを確認する
  - 失敗するテストがあればここで修正する

- [x] 10. ConversationInterestService — `discoverCandidates(pastQueries)` を TDD で追加する
  - [x] 10.1 **Red**: 失敗するテストを書く
    - `pastQueries` を渡すと `interestTopicExtractor.extract(snippets, maxTopics, pastQueries)` が呼ばれることを検証するテストを書く
    - この時点でコンパイルエラーになることを確認する
  - [x] 10.2 **Green**: `discoverCandidates(List<String> pastQueries)` を追加し、テストを通す
    - 既存の `discoverCandidates()` は `discoverCandidates(List.of())` に委譲する（後方互換性を維持）
    - テストが通ることを確認する
  - [x] 10.3 **Refactor**: 委譲パターンの重複がないか確認する
  - _Requirements: 2-A.1_

- [x] 11. InterestDiscoveryJob — 過去クエリ取得とスキップ判定を TDD で変更する
  - [x] 11.1 **Red**: 頻度制限スキップのテストを書く
    - `existsByTopicWithinHours` が `true` を返す場合に `searchKnowledgeService.search` が呼ばれないことを検証するテストを書く
    - この時点でテストが失敗することを確認する（現在は `existsBySearchQuery` のみでチェックしているため）
  - [x] 11.2 **Red**: 過去クエリが `discoverCandidates` に渡されることを検証するテストを書く
    - `listRecentSearchQueries` の戻り値が `discoverCandidates` の引数として渡されることを検証するテストを書く
  - [x] 11.3 **Green**: `discover()` を変更してテストを通す
    - 先頭で `listRecentSearchQueries(properties.getPastQueryLookbackDays())` を呼び出す
    - `discoverCandidates(pastQueries)` に変更する
    - スキップ判定を `existsByTopicWithinHours` → `existsBySearchQuery` の 2 段階に変更する
    - テストが通ることを確認する
  - [x] 11.4 **Refactor**: スキップ判定のロジックを読みやすく整理する
  - _Requirements: 2.2, 2-A.1, 2-A.4_

- [x] 12. InterestDiscoveryJob — プロパティテストを追加する
  - [x] 12.1 **Property 3: 頻度制限内のトピックはスキップされる**
    - `@Property` で `existsByTopicWithinHours` が `true` を返すモックを設定し、`searchKnowledgeService.search` が呼ばれないことを検証する
    - **Validates: Requirements 2.2**
  - [x] 12.2 **Property 4: 既存検索クエリはスキップされる**
    - `@Property` で `existsBySearchQuery` が `true` を返すモックを設定し、`searchKnowledgeService.search` が呼ばれないことを検証する
    - **Validates: Requirements 2-A.4**
  - [x] 12.3 **Property 5: 保存データの完全性**
    - `@Property` で任意の `InterestTopicCandidate` に対して、`save()` に渡される `topic`・`reason`・`searchQuery` が候補オブジェクトの対応フィールドと一致することを検証する
    - **Validates: Requirements 2.3**

- [x] 13. 既存テストを新しいシグネチャに合わせて更新する
  - `InterestDiscoveryJobTest` の `discoverCandidates()` 呼び出しを `discoverCandidates(anyList())` に更新する
  - `LlmInterestTopicExtractorTest` の `extract()` 呼び出しが新しいシグネチャと互換性があることを確認する
  - すべてのテストが通ることを確認する
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [x] 14. 最終チェックポイント — すべてのテストが通ることを確認する
  - `./mvnw test` を実行してすべてのテストが通ることを確認する
  - プロパティテストのイテレーション数が最低 100 回であることを確認する
  - 失敗するテストがあればここで修正する

---

## 注意事項

- **TDD の鉄則**: テストを書く前に実装を書かない。Red を確認してから Green に進む
- **最小実装**: Green フェーズでは「動く最小限」を書く。きれいにするのは Refactor フェーズ
- **小さいステップ**: 1 つのサブタスクで変更するファイルは原則 1〜2 ファイルに留める
- プロパティテストには `@Tag("Feature: interest-discovery, Property N: ...")` 形式でタグを付与すること
- `listRecentSearchQueries()` の失敗時は空リストにフォールバックして処理を継続することを推奨する
- jqwik は pom.xml に未追加のため、タスク 1 で追加が必要
