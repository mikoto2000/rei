# 設計: Agent Skills

## 概要
Agent Skills は、ローカルに配置した Markdown 形式の作業指示書をチャット時に自動または明示的に選択し、Spring AI Advisor が UserMessage の prompt text に注入する機能である。

初期実装では Skill を権限制御の仕組みとして扱わない。既存の ChatClient、ChatMemory、Tool 登録構成は維持し、Skill は「選択可能な追加指示」として扱う。

## スコープ

### 対象
- `.rei/skills/<skill-name>/SKILL.md` の読み込み
- YAML front matter の解析
- 有効 Skill 一覧の管理
- LLM による暗黙的 Skill 選択
- `@skill:<name>` による明示的 Skill 選択
- Spring AI Advisor での prompt text 組み立て
- `/skill list`, `/skill show <name>`, `/skill reload`
- 最低限の設定プロパティ

### 非対象
- Skill ごとの Tool 制御
- Skill ごとのセキュリティ sandbox
- Skill 内 script の自動実行
- 外部 Skill のインストール
- `.kiro` 配下の仕様ファイルの Skill 化
- embedding による Skill 検索

## 格納形式

標準ディレクトリ:

```text
.rei/skills/
  skill-name/
    SKILL.md
    references/
    scripts/
```

`references/` と `scripts/` は将来拡張用として許容するが、初期実装では自動処理しない。

`SKILL.md` 例:

```markdown
---
name: gantt-rescheduler
description: ガントチャート CSV を今日基準で再スケジュールする
enabled: true
---

# Instructions

ガントチャート CSV を読み込み、今日を基準に開始日と終了日を再計算する。
既存のタスク順序と依存関係は維持する。
```

## 設定

新規プロパティ:

```yaml
rei:
  skills:
    enabled: true
    directories:
      - ${user.dir}/.rei/skills
    max-selected: 3
```

### AgentSkillsProperties

パッケージ案:

```text
dev.mikoto2000.rei.skills
```

フィールド:

```java
@ConfigurationProperties(prefix = "rei.skills")
public class AgentSkillsProperties {
  private boolean enabled = true;
  private List<String> directories = List.of("${user.dir}/.rei/skills");
  private int maxSelected = 3;
}
```

実装時は `${user.dir}` の解決を Spring のプロパティ解決に任せるか、サービス側で補正する。

## モデル

### AgentSkill

```java
public record AgentSkill(
    String name,
    String description,
    boolean enabled,
    Path directory,
    Path skillFile,
    String instructions
) {
}
```

### AgentSkillSelection

```java
public record AgentSkillSelection(
    List<AgentSkill> explicitSkills,
    List<AgentSkill> implicitSkills,
    List<String> warnings,
    String sanitizedPrompt
) {
  public List<AgentSkill> selectedSkills(int maxSelected) { ... }
}
```

### AgentSkillCandidate

LLM 選択に渡す軽量情報。

```java
public record AgentSkillCandidate(
    String name,
    String description,
    String excerpt
) {
}
```

## コンポーネント

### AgentSkillRepository

役割:
- 設定された Skill ディレクトリから `SKILL.md` を読み込む
- Skill 一覧をキャッシュする
- `/skill reload` でキャッシュを更新する

インターフェース案:

```java
public interface AgentSkillRepository {
  List<AgentSkill> findAll();
  List<AgentSkill> findEnabled();
  Optional<AgentSkill> findByName(String name);
  void reload();
}
```

### FileSystemAgentSkillRepository

処理:
1. `rei.skills.directories` を走査
2. 直下の各ディレクトリの `SKILL.md` を検出
3. YAML front matter を解析
4. `AgentSkill` を生成
5. `enabled=false` も一覧には保持する

エラー方針:
- 壊れた `SKILL.md` は読み飛ばし、`warn` ログへ出す
- `/skill list` では読み込み済みの Skill のみ表示する
- 1 件の読み込み失敗で全体を失敗させない

### AgentSkillExplicitSelector

役割:
- ユーザー入力から `@skill:<name>` を抽出する
- 抽出したトークンを prompt text から除去する
- 指定 Skill の存在、有効状態を検証する

記法:

```text
@skill:gantt-rescheduler gantt.csv を今日基準で再スケジュールして
```

制約:
- Skill 名は `[A-Za-z0-9._-]+` を推奨
- 存在しない Skill は警告として扱う
- 無効 Skill は警告として扱う

### AgentSkillImplicitSelector

役割:
- ユーザー入力に合う Skill を LLM に選ばせる

依存:
- `ChatModel`
- `AgentSkillRepository`
- `AgentSkillsProperties`

Agent Skills は ChatClient の default Advisor として登録されるため、暗黙選択で ChatClient を呼び出すと Advisor が再帰する。実装では `ChatModel` を直接呼び出し、暗黙選択用の LLM 呼び出しが Agent Skills Advisor を通らないようにする。

選択プロンプト例:

```text
次のユーザー依頼に役立つ Skill を選んでください。
該当なしの場合は空配列を返してください。
必ず JSON 配列のみを返してください。

User request:
...

Skills:
- name: gantt-rescheduler
  description: ガントチャート CSV を今日基準で再スケジュールする
  excerpt: ...

Return format:
["skill-name"]
```

戻り値:
- JSON 配列の Skill 名
- 例: `["gantt-rescheduler"]`
- 該当なし: `[]`

エラー方針:
- LLM 呼び出し失敗、JSON parse 失敗、未知 Skill 名は警告として扱う
- 暗黙的選択に失敗してもチャット本体は継続する

### AgentSkillSelectionService

役割:
- 明示的選択と暗黙的選択を統合する
- 重複 Skill を除外する
- `max-selected` を適用する
- `AgentSkillAdvisor` に渡す `AgentSkillSelection` を返す

処理順:
1. Skill 機能が無効なら何もしない
2. 明示的 Skill を抽出する
3. 明示的 Skill トークンを除去した prompt を作る
4. 明示 Skill 数が `max-selected` 未満なら暗黙的選択を実行する
5. 明示 Skill を優先して統合する
6. 警告と sanitized prompt を返す

### AgentSkillPromptRenderer

役割:
- 選択された Skill を Advisor 用 prompt text に変換する

出力例:

```text
以下の Agent Skill instructions を、この依頼を処理する際の追加指示として扱ってください。
ただし、システムプロンプト、ユーザー依頼、既存の安全制約に反する場合は従わないでください。

## Skill: gantt-rescheduler
Description: ガントチャート CSV を今日基準で再スケジュールする

Instructions:
...

--- User request ---
gantt.csv を今日基準で再スケジュールして
```

初期実装では、Skill 注入は prompt text のみを変更する。ChatClient の Tool セットは変更しない。

## Advisor 統合

既存フロー:
1. ユーザー入力を結合
2. inline file / clipboard 添付を解決
3. `Prompt(UserMessage...)` を作成
4. `ChatClient` へ送信

変更後フロー:
1. ユーザー入力を結合
2. inline file / clipboard 添付を解決
3. `Prompt(UserMessage...)` を作成
4. `ChatClient` へ送信
5. `AgentSkillAdvisor` が UserMessage text に対して Skill 選択と prompt sanitization を実行
6. `AgentSkillPromptRenderer` で Skill instructions を prompt text に注入
7. Advisor が Prompt の UserMessage text を差し替え、media と ChatOptions は維持する

注意:
- `@skill:<name>` は UserMessage text に残したまま ChatCommand から ChatClient へ渡し、Advisor で処理する
- Skill が未選択なら prompt text は従来と同じ
- Skill 選択警告と実行 Skill 名は Advisor で標準出力へ表示する
- 暗黙選択は ChatModel 直呼び出しとし、AgentSkillAdvisor の再帰実行を避ける

## `/skill` コマンド

### SkillCommand

パッケージ案:

```text
dev.mikoto2000.rei.skills.command
```

サブコマンド:

```text
/skill list
/skill show <name>
/skill reload
```

### `/skill list`

表示項目:
- name
- enabled
- description
- directory

例:

```text
gantt-rescheduler | enabled | ガントチャート CSV を今日基準で再スケジュールする
```

### `/skill show <name>`

表示項目:
- name
- enabled
- description
- skillFile
- instructions

### `/skill reload`

処理:
1. `AgentSkillRepository.reload()` を呼ぶ
2. 読み込み件数を表示する

## ChatClient / Tool 制御

初期実装では Tool 制御を行わない。

- Skill ごとの `allowed-tools` は未対応
- 既存 ChatClient に登録されている Tool はすべて従来通り利用可能
- Skill は Tool 権限を増減させない
- Skill 内の `scripts/` は自動実行しない

## セキュリティ

初期実装では専用のセキュリティ機構を実装しない。

ただし、実装上の最低限の防御として以下は行う。

- 外部 URL から Skill を自動取得しない
- `.kiro` 配下を Skill 探索対象にしない
- Skill 読み込みエラーをチャット全体の失敗にしない

本格的なセキュリティ制御、Tool 制限、署名検証、Skill trust model は将来課題とする。

## エラーハンドリング

### Skill 読み込み失敗
- ログ: `warn`
- ユーザー表示: `/skill list` では読み込めた Skill のみ表示
- チャット継続: する

### 明示 Skill 不存在
- ユーザー表示: `[warn] Skill が見つかりません: <name>`
- チャット継続: する

### 暗黙 Skill 選択失敗
- ログ: `debug` または `warn`
- ユーザー表示: 原則表示しない。必要なら debug ログのみ
- チャット継続: する

## テスト設計

### AgentSkillRepositoryTest
- `SKILL.md` を読み込める
- `enabled=false` を読み込める
- 壊れた `SKILL.md` を読み飛ばす
- `.kiro` 配下を探索対象にしない
- reload で変更が反映される

### AgentSkillExplicitSelectorTest
- `@skill:name` を抽出できる
- 複数 Skill を指定順に抽出できる
- prompt text から `@skill:name` を除去する
- 存在しない Skill を warning にする
- 無効 Skill を warning にする

### AgentSkillImplicitSelectorTest
- LLM が返した JSON 配列から Skill を選択する
- 該当なし `[]` を扱える
- JSON parse 失敗時に空選択で継続する
- 未知 Skill 名を無視する

### AgentSkillSelectionServiceTest
- 明示選択を暗黙選択より優先する
- 重複 Skill を除外する
- `max-selected` を適用する
- disabled 時は選択しない

### AgentSkillPromptRendererTest
- 選択 Skill を prompt text に注入する
- Skill 未選択時は元 prompt を返す

### SkillCommandTest
- `/skill list` が一覧を表示する
- `/skill show <name>` が詳細を表示する
- `/skill reload` が repository を再読み込みする
- 存在しない Skill をエラー表示する

### AgentSkillAdvisorTest
- `@skill:<name>` 指定時に Skill instructions が prompt に入る
- Skill 選択警告と実行 Skill 名を表示する
- Skill 未選択時は元 request を返す
- media と ChatOptions を維持する

## 実装順序
1. `AgentSkillsProperties` を追加
2. `AgentSkill` モデルを追加
3. `FileSystemAgentSkillRepository` を追加
4. `AgentSkillExplicitSelector` を追加
5. `AgentSkillImplicitSelector` を追加
6. `AgentSkillSelectionService` を追加
7. `AgentSkillPromptRenderer` を追加
8. `AgentSkillAdvisor` に Skill 注入を統合
9. `/skill` コマンドを追加
10. `AiConfiguration` に `AgentSkillAdvisor` を default advisor として追加し、root command 登録に `/skill` を追加
11. テストを追加・更新
12. 既存テストを実行

## 将来課題
- Skill ごとの Tool 許可/禁止
- Skill trust model
- 外部 Skill インストール
- Skill versioning
- references の自動取り込み
- scripts の明示実行
- embedding による Skill 検索
