# 要件定義: Agent Skills

## 背景
ユーザーが特定領域の作業手順や判断基準を、会話ごとに手入力せず再利用したい要望がある。
Rei は既にローカルファイル操作、シェル実行、Web 検索、Google 連携などのツールを備えているため、作業ごとの指示書を Skill として読み込み、チャット時のプロンプトに反映できるようにしたい。

## 対象
- Codex 風のローカル Skill 格納ディレクトリ
- Skill メタデータと本文の読み込み
- LLM を使った Skill 選択
- Spring AI Advisor による prompt text 組み立て
- 最低限の `/skill` コマンド
- 明示的選択と暗黙的選択
- 最低限の設定項目

## 非対象
- Skill によるセキュリティ制御
- Skill ごとの Tool 制御
- 外部 Skill のインストール、更新、署名検証
- `.kiro` 仕様ファイルを Skill として読み込む機能
- Skill 内 script の自動実行

## 用語
- **Agent Skill**: 特定作業の手順、制約、判断基準を記述した Markdown ベースの指示書。
- **SKILL.md**: Skill の主ファイル。メタデータと本文を含む。
- **明示的選択**: ユーザーが `@skill:<name>` で利用する Skill を指定すること。
- **暗黙的選択**: ユーザー入力を基に、システムが LLM に候補 Skill を選択させること。
- **Skill 注入**: 選択された Skill の内容を Spring AI Advisor で prompt text に追加すること。

## 要件

### 要件 1: Codex 風の Skill 格納
**ユーザーストーリー:** ユーザーとして、作業別の指示書をローカルディレクトリに整理したいので、Codex 風の構造で Skill を配置できるようにしてほしい。

#### 受け入れ条件
1. THE システム SHALL `.rei/skills/<skill-name>/SKILL.md` を Skill の標準配置として扱う。
2. THE システム SHALL `SKILL.md` の YAML front matter から `name`, `description`, `enabled` を読み込む。
3. THE システム SHALL front matter 後の Markdown 本文を Skill instructions として読み込む。
4. WHEN `enabled` が `false` の Skill が存在する THEN システムは自動選択候補から除外する。
5. THE システム SHALL `.kiro` 配下の仕様ファイルを Skill として読み込まない。

### 要件 2: 最低限の設定
**ユーザーストーリー:** 開発者として、Skill 機能の有効化や探索対象を制御したいので、設定ファイルから最低限の挙動を変更できるようにしてほしい。

#### 受け入れ条件
1. THE システム SHALL `rei.skills.enabled` で Skill 機能全体の有効/無効を制御する。
2. THE システム SHALL `rei.skills.directories` で Skill 探索ディレクトリを指定できる。
3. THE システム SHALL `rei.skills.max-selected` でチャット 1 回あたりに注入する最大 Skill 数を指定できる。
4. WHEN `rei.skills.enabled` が `false` THEN システムは Skill 読み込み、選択、注入を行わない。
5. WHEN 設定が省略された THEN システムは `.rei/skills` を既定 Skill ディレクトリとして扱う。

### 要件 3: LLM による暗黙的 Skill 選択
**ユーザーストーリー:** ユーザーとして、毎回 Skill 名を指定したくないので、依頼内容に合う Skill を自動で選んでほしい。

#### 受け入れ条件
1. WHEN Skill 機能が有効で、利用可能な Skill が存在する THEN システムはユーザー入力と Skill 一覧を基に LLM へ選択を依頼する。
2. THE システム SHALL LLM には Skill 名、説明、必要に応じて本文の短い抜粋を渡す。
3. THE システム SHALL LLM の選択結果から `max-selected` 件まで Skill を採用する。
4. WHEN LLM が該当なしと判断した THEN システムは Skill を注入しない。
5. WHEN Skill 選択に失敗した THEN システムは通常チャットを継続し、Skill 選択失敗だけでチャットを失敗させない。

### 要件 4: 明示的 Skill 選択
**ユーザーストーリー:** ユーザーとして、特定の Skill を確実に使わせたいので、入力内で Skill 名を明示できるようにしてほしい。

#### 受け入れ条件
1. WHEN ユーザー入力に `@skill:<name>` が含まれる THEN システムは指定された Skill を選択する。
2. WHEN 複数の `@skill:<name>` が含まれる THEN システムは指定順に Skill を選択する。
3. WHEN 明示指定された Skill が存在しない、または無効である THEN システムは警告を表示し、該当 Skill を注入しない。
4. THE システム SHALL 明示的に選択された Skill を暗黙的選択より優先する。
5. THE システム SHALL `@skill:<name>` トークンを最終ユーザー依頼文から除去する。

### 要件 5: Advisor による prompt text 組み立て
**ユーザーストーリー:** 開発者として、チャットコマンド本体を肥大化させずに Skill を導入したいので、Spring AI Advisor として prompt text を組み立ててほしい。

#### 受け入れ条件
1. THE システム SHALL 選択された Skill instructions を Spring AI Advisor で UserMessage の prompt text に追加する。
2. THE システム SHALL 既存の ChatClient、ChatMemory、ツール登録構成を維持する。
3. THE システム SHALL Skill 注入後もファイル添付、クリップボード添付、モデル指定、回答開始時間表示の既存挙動を維持する。
4. WHEN Skill が選択されない THEN システムは従来通りの prompt text でチャットを実行する。
5. THE システム SHALL 暗黙 Skill 選択用の LLM 呼び出しが Agent Skills Advisor を再帰的に通らないようにする。

### 要件 6: `/skill` コマンド
**ユーザーストーリー:** ユーザーとして、利用可能な Skill を確認したいので、最低限の Skill 管理コマンドがほしい。

#### 受け入れ条件
1. THE システム SHALL `/skill list` で読み込み可能な Skill 一覧を表示する。
2. THE システム SHALL `/skill show <name>` で指定 Skill の詳細を表示する。
3. THE システム SHALL `/skill reload` で Skill 定義を再読み込みする。
4. WHEN Skill が存在しない THEN `/skill list` は空であることを分かるメッセージを表示する。
5. WHEN 存在しない Skill を `/skill show` で指定した THEN システムはエラーメッセージを表示する。

### 要件 7: セキュリティと Tool 制御の扱い
**ユーザーストーリー:** 開発者として、初期実装の責務を明確にしたいので、セキュリティ制御と Tool 制御をこの機能では実装しないことを明示してほしい。

#### 受け入れ条件
1. THE システム SHALL Skill による Tool 許可/禁止制御を行わない。
2. THE システム SHALL 既存 ChatClient に登録されている Tool を従来通りすべて利用可能とする。
3. THE システム SHALL Skill instructions を権限昇格の仕組みとして扱わない。
4. THE システム SHALL Skill 内 script を自動実行しない。
5. THE システム SHALL 外部 URL から Skill を自動取得しない。

### 要件 8: 既存機能への非影響
**ユーザーストーリー:** 開発者として、Agent Skills 追加で既存チャットやツール機能を壊したくないので、既存挙動への影響を最小化してほしい。

#### 受け入れ条件
1. THE システム SHALL Skill が無効な場合、既存チャット挙動を変更しない。
2. THE システム SHALL 既存の Tool 呼び出し、音声通知、入力表示ポリシーを変更しない。
3. THE システム SHALL Agent Skills に関する単体テストを追加する。
4. THE システム SHALL 既存の主要テストが継続して成功することを確認できる。
