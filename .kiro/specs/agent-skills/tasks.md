# Implementation Plan: Agent Skills

## Overview

Agent Skills は、`.rei/skills/<skill-name>/SKILL.md` に配置された Markdown 指示書を読み込み、ユーザー入力に対して明示的または LLM による暗黙的選択を行い、`ChatCommand` が組み立てる prompt text に注入する機能である。

実装は t_wada の TDD に則り、各タスクを Red → Green → Refactor の小さいサイクルで進める。コンパイルエラーも Red として扱う。各チェックポイントでは対象テストを実行し、必要に応じてコミット可能な単位に整理する。

## Task Dependency Graph

```text
1 (properties) → 2 (model) → 3 (repository)
                                   ↓
4 (explicit selector) → 6 (selection service) → 7 (prompt renderer) → 8 (ChatCommand integration)
5 (implicit selector) ─────────────┘
                                   ↓
9 (/skill command) → 10 (configuration/root registration) → 11 (integration checkpoint)
```

## Tasks

- [ ] 1. AgentSkillsProperties を実装する（Red → Green）
  - [ ] 1.1 **Red**: 設定プロパティの失敗テストを書く
    - `src/test/java/dev/mikoto2000/rei/skills/AgentSkillsPropertiesTest.java` を新規作成する
    - デフォルト値として `enabled=true`、`directories` が `.rei/skills` 相当、`maxSelected=3` になることを検証する
    - この時点でコンパイルエラーになることを確認する
  - [ ] 1.2 **Green**: `AgentSkillsProperties` を実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/skills/AgentSkillsProperties.java` を作成する
    - `@ConfigurationProperties(prefix = "rei.skills")` を付与する
    - `enabled`, `directories`, `maxSelected` を持たせる
    - `maxSelected` が 1 未満の場合は 1 として扱う補正を実装する
  - [ ] 1.3 **Refactor**: プロパティ名とデフォルト値を読みやすく整理する
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [ ] 2. AgentSkill モデルを実装する（Red → Green）
  - [ ] 2.1 **Red**: Skill モデルの失敗テストを書く
    - `src/test/java/dev/mikoto2000/rei/skills/AgentSkillTest.java` を新規作成する
    - `name`, `description`, `enabled`, `directory`, `skillFile`, `instructions` を保持できることを検証する
    - 空の description や instructions を許容するかを要件に合わせて固定する
    - この時点でコンパイルエラーになることを確認する
  - [ ] 2.2 **Green**: `AgentSkill` record を実装してテストを通す
    - `src/main/java/dev/mikoto2000/rei/skills/AgentSkill.java` を作成する
    - 必要な全フィールドを record として定義する
  - _Requirements: 1.1, 1.2, 1.3_

- [ ] 3. FileSystemAgentSkillRepository を実装する（Red → Green → Refactor）
  - [ ] 3.1 **Red**: 正常な `SKILL.md` を読み込む失敗テストを書く
    - `src/test/java/dev/mikoto2000/rei/skills/FileSystemAgentSkillRepositoryTest.java` を新規作成する
    - 一時ディレクトリに `.rei/skills/sample/SKILL.md` を作成する
    - YAML front matter の `name`, `description`, `enabled` と Markdown 本文を読み込めることを検証する
    - この時点でコンパイルエラーになることを確認する
  - [ ] 3.2 **Green**: 最小の repository 実装で正常系テストを通す
    - `AgentSkillRepository` インターフェースを作成する
    - `FileSystemAgentSkillRepository` を作成する
    - `findAll()`, `findEnabled()`, `findByName(String)`, `reload()` を実装する
  - [ ] 3.3 **Red**: disabled Skill と壊れた Skill のテストを追加する
    - `enabled=false` は `findAll()` に含まれ、`findEnabled()` から除外されることを検証する
    - front matter が壊れた `SKILL.md` は読み飛ばされることを検証する
    - 1 件の壊れた Skill があっても他の Skill を読み込めることを検証する
  - [ ] 3.4 **Green**: disabled と読み込み失敗の扱いを実装する
    - 読み込み失敗は `warn` ログへ出し、全体は継続する
    - `enabled` 省略時は `true` とする
  - [ ] 3.5 **Red**: `.kiro` 配下を Skill として読み込まないテストを追加する
    - 設定ディレクトリに `.kiro` を指定しても Skill として扱わないことを検証する
  - [ ] 3.6 **Green**: `.kiro` 配下の除外を実装する
  - [ ] 3.7 **Refactor**: front matter 解析処理を private メソッドへ整理する
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [ ] 4. AgentSkillExplicitSelector を実装する（Red → Green → Refactor）
  - [ ] 4.1 **Red**: `@skill:<name>` 抽出の失敗テストを書く
    - `src/test/java/dev/mikoto2000/rei/skills/AgentSkillExplicitSelectorTest.java` を新規作成する
    - `@skill:gantt-rescheduler` を抽出できることを検証する
    - 抽出後の prompt text から token が除去されることを検証する
    - この時点でコンパイルエラーになることを確認する
  - [ ] 4.2 **Green**: 明示選択の最小実装を行う
    - `AgentSkillExplicitSelector` を作成する
    - `@skill:<name>` の正規表現抽出を実装する
    - `sanitizedPrompt` を返す結果型を作成する
  - [ ] 4.3 **Red**: 複数指定、不存在、disabled のテストを追加する
    - 複数 Skill が指定順に返ることを検証する
    - 存在しない Skill は warning になることを検証する
    - disabled Skill は warning になることを検証する
  - [ ] 4.4 **Green**: 複数指定と warning を実装する
  - [ ] 4.5 **Refactor**: token 正規表現と warning メッセージを定数化する
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [ ] 5. AgentSkillImplicitSelector を実装する（Red → Green → Refactor）
  - [ ] 5.1 **Red**: LLM JSON 配列選択の失敗テストを書く
    - `src/test/java/dev/mikoto2000/rei/skills/AgentSkillImplicitSelectorTest.java` を新規作成する
    - mock `ChatClient` が `["skill-a"]` を返すと該当 Skill が選択されることを検証する
    - この時点でコンパイルエラーになることを確認する
  - [ ] 5.2 **Green**: LLM 選択の最小実装を行う
    - `AgentSkillImplicitSelector` を作成する
    - `ObjectProvider<ChatClient>` を使って循環依存を避ける
    - Skill 名、説明、短い excerpt を含む選択プロンプトを作る
    - JSON 配列を parse して Skill 一覧へ変換する
  - [ ] 5.3 **Red**: 該当なし、未知 Skill、JSON parse 失敗のテストを追加する
    - `[]` は空選択になることを検証する
    - 未知 Skill 名は無視されることを検証する
    - JSON parse 失敗時に空選択で継続することを検証する
  - [ ] 5.4 **Green**: 異常系を実装する
  - [ ] 5.5 **Refactor**: 選択プロンプト構築と JSON parse を private メソッドへ分離する
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [ ] 6. AgentSkillSelectionService を実装する（Red → Green → Refactor）
  - [ ] 6.1 **Red**: 明示選択優先の失敗テストを書く
    - `src/test/java/dev/mikoto2000/rei/skills/AgentSkillSelectionServiceTest.java` を新規作成する
    - 明示選択された Skill が暗黙選択より先に採用されることを検証する
    - この時点でコンパイルエラーになることを確認する
  - [ ] 6.2 **Green**: 選択統合の最小実装を行う
    - `AgentSkillSelectionService` を作成する
    - 明示選択結果と暗黙選択結果を統合する
    - 重複を除外する
  - [ ] 6.3 **Red**: `max-selected` と disabled のテストを追加する
    - `max-selected` 件までに制限されることを検証する
    - `rei.skills.enabled=false` の場合は Skill 選択を行わないことを検証する
  - [ ] 6.4 **Green**: `max-selected` と enabled 制御を実装する
  - [ ] 6.5 **Refactor**: 統合ロジックを読みやすく整理する
  - _Requirements: 2.4, 3.3, 4.4_

- [ ] 7. AgentSkillPromptRenderer を実装する（Red → Green）
  - [ ] 7.1 **Red**: Skill 注入 prompt の失敗テストを書く
    - `src/test/java/dev/mikoto2000/rei/skills/AgentSkillPromptRendererTest.java` を新規作成する
    - 選択 Skill の name, description, instructions が prompt text に含まれることを検証する
    - 元のユーザー依頼が `--- User request ---` 以降に含まれることを検証する
    - この時点でコンパイルエラーになることを確認する
  - [ ] 7.2 **Green**: renderer を実装してテストを通す
    - `AgentSkillPromptRenderer` を作成する
    - Skill 未選択時は元 prompt をそのまま返す
  - _Requirements: 5.1, 5.4_

- [ ] 8. ChatCommand に Agent Skills を統合する（Red → Green → Refactor）
  - [ ] 8.1 **Red**: 明示 Skill 注入の ChatCommand テストを書く
    - 既存 `ChatCommandTest` または新規 `ChatCommandAgentSkillsTest` に追加する
    - `@skill:sample hello` 実行時に `ChatClient.prompt(Prompt)` へ渡る UserMessage text に Skill instructions が含まれることを検証する
    - `@skill:sample` が最終ユーザー依頼から除去されることを検証する
  - [ ] 8.2 **Green**: `ChatCommand` に Skill 選択と prompt rendering を統合する
    - `Optional<AgentSkillSelectionService>` と `Optional<AgentSkillPromptRenderer>` を constructor 引数に追加する
    - Skill 機能 Bean がない場合は従来挙動にする
    - inline file / clipboard resolver より前に `@skill` を処理する
  - [ ] 8.3 **Red**: 暗黙 Skill 注入と disabled の ChatCommand テストを追加する
    - 暗黙選択された Skill が prompt に入ることを検証する
    - Skill 機能 disabled 相当では従来 prompt になることを検証する
  - [ ] 8.4 **Green**: 暗黙選択と disabled 経路を通す
  - [ ] 8.5 **Refactor**: `ChatCommand` の prompt 準備処理を小さな private メソッドへ整理する
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 8.1, 8.2_

- [ ] 9. `/skill` コマンドを実装する（Red → Green）
  - [ ] 9.1 **Red**: `/skill list` の失敗テストを書く
    - `src/test/java/dev/mikoto2000/rei/skills/command/SkillCommandTest.java` を新規作成する
    - repository mock から返した Skill 一覧が表示されることを検証する
    - この時点でコンパイルエラーになることを確認する
  - [ ] 9.2 **Green**: `SkillCommand` と `list` サブコマンドを実装する
    - `src/main/java/dev/mikoto2000/rei/skills/command/SkillCommand.java` を作成する
    - `list` で name, enabled, description, directory を表示する
  - [ ] 9.3 **Red**: `/skill show <name>` と `/skill reload` のテストを追加する
    - `show` が instructions を含む詳細を表示することを検証する
    - 存在しない Skill でエラー表示することを検証する
    - `reload` が repository.reload() を呼ぶことを検証する
  - [ ] 9.4 **Green**: `show` と `reload` を実装する
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [ ] 10. Spring 設定と root command 登録を実装する（Red → Green）
  - [ ] 10.1 **Red**: Spring context の失敗テストを書く
    - `ReiApplicationTests` または専用 configuration test で `AgentSkillsProperties`, repository, selector, renderer, `/skill` command が Bean 登録されることを検証する
    - この時点で不足 Bean により失敗することを確認する
  - [ ] 10.2 **Green**: Bean 登録を実装する
    - `@EnableConfigurationProperties(AgentSkillsProperties.class)` を追加する
    - repository / selector / selection service / renderer を `@Component` または `@Service` として登録する
    - root command に `SkillCommand` を追加する
  - _Requirements: 2.1, 6.1, 8.1_

- [ ] 11. チェックポイント — Agent Skills 関連テストを実行する
  - [ ] 11.1 `./mvnw test "-Dtest=AgentSkill*Test,FileSystemAgentSkillRepositoryTest,SkillCommandTest,ChatCommandAgentSkillsTest"` を実行する
  - [ ] 11.2 失敗したテストがあれば Red/Green の粒度に戻して修正する
  - [ ] 11.3 Agent Skills 関連の変更がコミット可能な状態であることを確認する

- [ ] 12. 回帰チェックポイント — 主要既存テストを実行する
  - [ ] 12.1 `./mvnw test "-Dtest=ChatCommandTest,ChatCommandNarrationTest,ToolsTest,ReiApplicationTests"` を実行する
  - [ ] 12.2 失敗したテストがあれば既存挙動を壊していないか確認して修正する
  - [ ] 12.3 必要に応じて README または config template 更新タスクを追加する

- [ ] 13. 最終確認
  - [ ] 13.1 `git status --short` で変更ファイルを確認する
  - [ ] 13.2 未追跡の実行時ディレクトリ（`.rei`, `.m2`, `.jqwik-database` など）をコミット対象から除外する
  - [ ] 13.3 実装差分とテスト結果を要約する
