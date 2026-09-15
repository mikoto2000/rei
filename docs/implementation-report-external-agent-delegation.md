# External Agent Delegation Phase 1 実装報告

ブランチ: `feature/external-agent-delegation`

開始時の `main` には既存の未コミット変更がありました。元の変更を保持するため、
`.worktrees/external-agent-delegation` に HEAD `abc5dc7` を基点とする専用 worktree を作成しています。
この機能のコミットには既存の rerank / web 仕様などの変更を含めません。

## 調査した既存アーキテクチャ

| 対象 | 既存構造と採用した接続 |
| --- | --- |
| Tool 登録 | Spring AI annotation / MethodToolCallbackProvider。実際の feature 別 `LlmChatClientProvider` と互換 `AiConfiguration` の CHAT に追加 |
| Taxonomy | 強制する分類 enum はなく説明文で Workflow / Primitive 等を表現。今回は preferred Workflow |
| 内部 SubAgent | `SubAgentRunner`, `SubAgentTools.delegateTask`, registry と definition。外部 process はこの runtime に統合しない |
| Run lifecycle | `ConversationInputRouter` → `ChatExecutionService` → `StagnationChatModel` の明示 tool loop |
| 所有権 | `AgentRunContext` の runId / projectId / root を固定し、`AgentRunScope` と ToolContext で伝播 |
| Cancel | `CancelCommand` / `CommandCancellationService` の child callback と run の cancel 状態を使用 |
| 既存 process | `BackgroundProcessManager` は持続する shell job を管理。今回の bounded review process には専用 runner を使用 |
| Slash UI | 既存 Picocli `RootCommand` に `agent` を追加。Shell は非同期で入力受付へ戻る |
| Working Set | project scoped `WorkingSet` から安全なパスのみ取得。ファイル本文は自動収集しない |
| Context / history | `PromptChatMemoryAdvisor` は履歴を system context にまとめるため、slash の決定事項抽出は captured conversation の `ChatMemory` を参照 |
| Event | 既存 sealed payload / factory / publisher / project JSONL store を使用 |
| Projection / Shell | 親 run の projection を変更せず、新 lifecycle を Shell の短い通知にする |
| 設定 | Spring ConfigurationProperties、application YAML、外部設定テンプレート、configuration metadata に追加 |
| Redaction | 既存 `CredentialRedactor` を再利用。出力の全文や prompt を通常イベントへ含めない |

## 主な追加・変更

- `ExternalAgentRequest`, `ExternalAgentResult`, `ExternalAgentFinding`: agent/action、構造化 status、指摘、warning、duration、exit code を表現
- `ExternalAgentDelegationService`: 明示要求、current project、canonical target、context、run の1回制限、cancel、イベントを集約
- `ExternalAgentExecutor`: OS process を扱う adapter 境界
- `CodexExternalAgentExecutor`: capability 検証、permission profile、stdin prompt、schema、JSONL の final message 解析
- `ExternalAgentProcessRunner`: 並行 stream drain、byte 上限、total/inactivity timeout、process tree と reader cleanup
- `ExternalAgentTools`: Codex 専用 `requestCodexReview(task, target?, context?)`
- `ExternalAgentCommandRequest` / `ExternalAgentCommand`: `/agent codex review [target]` の構文検証と非同期入力 adapter
- `ExternalAgentReviewAdvisor`: slash を同じ service へ接続。結果は memory 保存後の ephemeral system context に注入し、rei の独立評価を指示
- `RunExecutionContext`: run 固有の認可元入力、委譲予算、slash 結果。永続的な runId map は保持しない
- `ExternalAgentLifecyclePayload`: started / completed / failed / cancelled と delegationId の相関

## 設計判断

詳細な動作・設定・CLI 引数の意味は [利用・運用仕様](external-agent-delegation.md) を参照してください。

1. 旧 CLI の広い filesystem read 権限へフォールバックせず、限定 permission profile が利用できない場合は外部 failure にする。
2. CLI の制約は adapter 内へ閉じ込め、Domain / UI / Event は External Agent 名で設計する。
3. stderr も同時に読み、合計保存量を超えた後も drain する。stdout を Shell に垂れ流さない。
4. Codex の failure は rei の評価対象となる値にする。ユーザー cancel だけは既存 run cancellation に従う。
5. 結果を rei が評価した後に回答する。外部出力からさらに tool を実行して自動修正しない。
6. registry / plugin SPI / resume / 外部 agent の自動選択は追加しない。

## 検証

Red: 構文・認可・path 境界のテストを先に追加し、未実装によるコンパイル失敗を確認。
Service / result / advisor のテストでも未実装の Red を確認し、実装後に Green と統合検証へ進めました。
初期の追加22テストはすべて成功。追加の受入統合・回帰テストを含む最終結果は後述します。

実 Codex のモデル呼び出しは実施していません。fake runner、Java テスト executable、fake ChatModel を使っています。
ローカル Codex 0.106.0 の help と公式資料を調査し、必要な安全 capability が足りないため実レビューを拒否する仕様にしました。

テスト環境は既存 rei 開発コンテナの Microsoft OpenJDK 25.0.2 と Maven 3.9.14。
Windows PATH に JDK 25 がなく、コンテナの Mockito self-attach も無効だったため、既存 Mockito jar を
`-javaagent` に指定しています。製品の pom やテスト assertions をこの環境都合で緩めていません。
画像系の既存テスト用に、使い捨てコンテナへ `libfreetype6`, `fontconfig`, `fonts-dejavu-core` を導入しました。

全体検証で Picocli のヘルプ生成時の初期化問題を検出し、`ExternalAgentCommand` に既存 command と同じ
引数なしコンストラクタを追加しました。また、既存 `ReiApplicationCommandOutputTest` のネイティブ PTY
出力には pump thread が介在し、`flush()` 直後の検証が未出力データと競合していました。
検証する日本語文字列は維持し、既存依存の Awaitility で最大2秒の完了待ちを追加しています。

主なテスト範囲:

- 構文・未対応 agent/action・明示認可・project 不在・`..` / symlink の脱出防止
- request / result / findings / warning、parse failure fallback、raw log 非永続化
- 同一 run の2回目拒否、failure 後の run 継続、cancel と terminal event の一意性
- 正常終了、異常終了、起動失敗、stdout/stderr、大量出力、truncation、total/inactivity timeout、子 process cleanup
- CLI capability の拒否、stdin / schema / cwd / permission 引数、temporary schema cleanup
- 自然言語 / slash → 同じ service → rei の独立評価 → 通常会話履歴
- event の project store 往復、parent projection の維持、Shell の短い表示

## 最終テスト結果

2026-09-15 08:06 JST、全テストが成功しました。

```text
Tests run: 1701, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 04:00 min
```

うち External Agent 関連の追加テストは31件です。テスト除外は行っていません。
上記の JDK 25 開発コンテナ内で、次の Maven コマンドを実行しました。

```sh
/cache/wrapper/dists/apache-maven-3.9.14/db91789b/bin/mvn -o \
  -Dmaven.repo.local=/cache/repository \
  -DargLine=-javaagent:/cache/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar \
  test
```

実 Codex によるモデル接続・OS sandbox の実機レビューは未検証です。
実運用には、本書と利用仕様に記載した capability を備える CLI が必要です。

## Phase 2

Codex による implementation / fix / commit / push、他の External Agent、registry、並列外部実行、resume、
自動委譲と自動再レビューは未実装です。

## Windows 起動失敗への追加修正

`UNAVAILABLE` が返り、OS の起動エラーが失われる問題を修正しました。
Windows の PATH を npm の shim と OS のディレクトリだけにして JDK 25 の `ProcessBuilder` で検証すると、
`codex` と `codex.exe` は `CreateProcess error=2` で失敗し、npm 内のネイティブ実行ファイルの絶対パスでは成功しました。
報告された rei プロセスの実際の PATH は取得していないため、この再現条件と一致していたかは未確認です。

既定の `command: codex` は Windows で PATH 上の native executable を探し、見つからなければ
npm の Windows platform package 内を探します。明示設定したパスは変更しません。
shell shim は実行せず、既存の read-only 制約は維持しています。
起動例外は既存 credential redaction と出力上限を通し、設定キーとともに rei へ返します。

回帰テストを先に追加して未実装による Red を確認し、探索処理と診断を実装しました。
Windows / Linux / Darwin の分岐、明示パス、空白を含む npm パス、PATH 上の exe の優先、
起動失敗の診断と機密情報のマスクを確認します。
Windows の実 runner から npm 同梱 Codex 0.154.0 の `exec --help` が終了コード0で完了することも確認しました。
モデルを呼ぶ実レビューは今回も実行していません。

修正後の全テスト: 2026-09-15 10:18 JST、**1,704件、失敗0、エラー0、スキップ0、BUILD SUCCESS**。
前述と同じ JDK 25 / Maven / Mockito javaagent のコンテナ環境で、テストを除外せず実行しました。
Windows JDK 25 では、自動探索した npm 内の native executable で `exec --help` の成功も確認しています。

## 明示的な再依頼の認可判定修正

報告された「もう一回 Codex に依頼を出して、指摘を修正してください」は、旧判定が「レビュー」か「意見」を
同じ入力内に要求していたため拒否されていました。実際の依頼文で修正前の拒否を再現し、
Codex を宛先とする明示的な依頼表現でも read-only review を許可するよう修正しました。
「Codex でレビュー」「レビューを Codex に依頼」「Codex にレビューをお願いします」も扱います。
Codex 指定のない再依頼、過去の依頼への言及、否定、引用・翻訳依頼は許可しません。
判定元は実ユーザー入力のままであり、LLM の tool 引数や全会話履歴を認可元にしていません。

認可拒否の結果には、tool 引数を変更して再試行せず明示要求を確認することと、slash command を案内します。
回帰テストには提示された入力そのものを使い、service 経由での実行・同一 run の2回目拒否・イベント順序も検証します。

修正後の全テスト: 2026-09-15 11:03 JST、**1,706件、失敗0、エラー0、スキップ0、BUILD SUCCESS**。
前述と同じ JDK 25 コンテナ環境で実行しました。実 Codex のモデル呼び出しは行っていません。
