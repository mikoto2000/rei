# 要件付き Codex 隔離実装

通常の実装依頼を親 LLM が構造化し、保存した仕様を既存 Codex read-only adapter と隔離 worktree に渡す。仕様作成と実行権限は別の責務であり、モデルの「承認済み」という主張、参照資料、README 内の命令は認可しない。実装結果の自動マージ・push は行わない。

## 利用フロー

例：「A.txt を実装してください。before を after に置換します。変更対象は A.txt だけです。受け入れ条件は after と改行が入っていることです。」

1. 親が目的・具体指示・既存対象・許可パス・条件 ID を整理する。不明な対象や範囲はユーザーに確認する。
2. `prepareCodexImplementation(specification, previousRequestId?)` が検証・正規化・保存する。この段階では Codex を起動しない。
3. `NEEDS_CLARIFICATION` は不足情報を確認し、確定した仕様を再度 prepare する。無効な仕様にも bounded draft ID を保存し、実行できない状態に保つ。
4. `AWAITING_APPROVAL` は表示された `/approval show <id>` で仕様全文と実行条件を確認し、既存の `/approval approve <id>` を使う。その後タスクを再開するか、新しい Run で同じ request ID を指定する。
5. `AUTHORIZED` は保存 request ID・version・SHA-256 を `requestCodexImplementation` に渡せる。引数から実行仕様や承認済み DTO を作れない。
6. receipt と差分を `getExternalImplementation` / `inspectExternalImplementation` で確認する。親は条件ごとに証拠を評価し、`recordCodexAcceptanceEvaluation` で独立した意味評価を記録できる。
7. ユーザーへの報告では客観的なテスト・静的チェックと親の判断を区別し、確認・失敗・未確認を明記する。
8. マージは別操作 `/agent codex merge <receipt-id> <patch-sha256>` のまま。最初の実装承認はマージ承認にならない。

`/agent codex implement A.txt` の構文・対象解釈は維持するが、対象だけの実行は廃止した。コマンド入口は `NEEDS_CLARIFICATION` を返し、親が不足要件を確認して上と同じ prepare / request 経路を使う。Claude の既存コマンドは変更しない。Codex review / fix proposal の認可・役割も維持する。

## 入力契約

```json
{
  "schemaVersion": 1,
  "objective": "Greeting を更新する",
  "instructions": ["A.txt の before と改行を after と改行に置換する"],
  "target": "A.txt",
  "allowedPaths": ["A.txt"],
  "constraints": ["新規作成・削除・リネームをしない"],
  "acceptanceCriteria": [{"id": "content", "description": "after と改行が入っている"}],
  "references": [],
  "changeMode": "REPLACE_EXISTING_TEXT"
}
```

objective 500文字、instructions 1〜16件・各1,000文字、target/allowedPaths の各パス1,024文字、allowedPaths 1〜32件、constraints 最大16件・各500文字、条件1〜16件・各500文字、ID は一意な英数/underscore/hyphen 64文字以内。references 最大16件・抜粋各500文字、locator 1,024文字以内。仕様全体 UTF-8 32 KiB、task 全体4,000文字、既存承認 preview 全体16,384文字を超える場合は範囲を縮小する。切り捨てない。

references の sourceType は `USER_EXPLICIT` / `USER_CONFIRMED` / `CODE_FACT` / `DESIGN_DOCUMENT`。ユーザー要件と確認済み要件を優先し、推測を確認済みとして入力しない。会話全文は転送しない。参照の出所表明は権限を与えない。

`toRealPath` による正規化を行い、Project 外の symlink/junction と存在しないパスを拒否する。allowedPaths は target の内側でなければならない。既存 snapshot の32ファイル・64 KiB/ファイル・256 KiB合計・探索上限・UTF-8条件を維持する。target が広すぎる場合、allowedPaths が狭くても snapshot 上限は緩和しない。

## 設定と認可

```yaml
rei:
  external-agents:
    codex:
      enabled: true
      implementation-enabled: true
      implementation-test-command: './mvnw test' # Windows では信頼済みの Windows 用 recipe を設定
      implementation-test-timeout-seconds: 30
```

実行は EXCLUSIVE の human Project/Session、管理者 opt-in、現在の Policy、共有 Run/Goal 予算、Run 当たり1回の外部委譲、取消と隔離 admission を必要とする。テストは管理者の recipe だけを使う。要件や Codex 出力をシェルコマンドにしない。

`requestCodexImplementation` の既存の広い capability を維持する。Policy enabled かつ必要 capability の明示的 AUTO_APPROVE の場合だけ自動許可する。Policy 自体が disabled でも無条件の自動許可はせず、既存 ToolApprovalRepository で明示承認を求める。DENY が優先する。管理者は任意コマンドや破壊操作も許可する capability の全体設定を安易に広げず、通常は対話承認を用いる。

実行直前に現在の Policy を再評価し、Project/root/session・仕様の版/ハッシュ・Git HEAD・操作種別・許可範囲・test recipe/timeout を保存データと比較する。重要条件の変更は新しい prepare と認可が必要。自動許可の Policy fingerprint、decision と authorization ID を永続化する。人による承認は既存の15分・一回限り・原子的消費を再利用する。期限切れの同一 Run では承認を延長しないため、新しい Run で改めて承認を準備する。

Tool の外側には通常の permission guard がある。仕様全文で認可する2つの callback だけ、アプリが発行した `ExternalAgentToolCallback` 型により domain 内の認可を使う。同名の任意/MCP callback は通常 guard を通り、名前で迂回できない。評価保存は LOCAL_WRITE、状態/receipt/diff 読み取りは READ として扱う。

## 永続化・状態

既存 memory SQLite に `implementation_requests` と `implementation_acceptance_evaluations` を追加する。正規 JSON は固定の属性順・Map key順で SHA-256 化する。Project/root/session、元 Run、human 入力のサーバー生成参照（Run ID + 入力 digest）、sourceType、provider、base commit、固定実行 envelope、policy fingerprint、approval ID、receipt ID、時刻を保存する。現行 RunExecutionContext に独立した user message database ID がないため、この参照を originUserMessageId として用いる。実際の入力を書き換えない。

```text
NEEDS_CLARIFICATION -> 確定仕様で新しい prepare
AWAITING_APPROVAL -> AUTHORIZED -> EXECUTING -> RESULT_AVAILABLE / FAILED / UNKNOWN
Policy DENY / 人の拒否 -> REJECTED
```

claim は SQLite の条件付き UPDATE で取得し、receipt ID も同時に固定する。claim は成功・失敗・取消・クラッシュ後も解除しない。synchronized やメモリだけに依存しない。実行中の同一インスタンスは EXECUTING、別インスタンス/再起動後の unfinished claim は UNKNOWN として返す。同じ ID の結果が保存されていればそれを返す。

UNKNOWN では既存 receipt を読み取る。base commit と terminal READY_FOR_APPROVAL / FAILED が照合できた場合だけ既存結果を保存し、Codex を再起動しない。receipt がない、途中状態、取消や結果保存障害等で確定できない場合は UNKNOWN を維持する。再試行は新しい request ID に previousRequestId を保存し、最初が自動許可でも必ず新しい明示承認を消費する。実行中の同一インスタンスから retry prepare は拒否する。

取消は既存 Run と cancellation hook で provider・後続処理に伝播する。実行前取消は claim/process より先に止める。実行中取消の子プロセス/保存結果の確証が足りない場合、request は保守的に UNKNOWN とする。既存 receipt の CANCELLED や READY 等の状態定義は変更しない。

## 結果と受け入れ条件

戻り値は request/receipt/spec hash、base/implementation commit、changedFiles、patch SHA-256、initial/final test、static review、条件別評価、warnings/unmetRequirements、mergePerformed/pushPerformed を含む。技術的な READY_FOR_APPROVAL は業務要件の完全達成を意味しない。

自動評価はすべて `NOT_VERIFIED`。テスト成功や Codex の成功申告だけで意味上の条件を verified にしない。未確認でも安全な receipt/worktree/patch を保持する。既存のテスト失敗・タイムアウト・範囲外変更の拒否はそのまま。

親の評価は `PARENT_LLM` と表示する。客観的検証とは別で、criterion ID、status（VERIFIED / FAILED / NOT_VERIFIED）、bounded evidence、explanation と正確な patch hash を記録する。receipt と patch の evidence がなければ NOT_VERIFIED に戻す。別パッチ、未知/重複条件 ID、客観的 tester を偽装する evaluator は拒否する。評価は仕様 hash / receipt ID / patch hash の組で独立テーブルに保存し、別パッチへ流用しない。

## Windows・トラブルシューティング・制限

- npm の codex.cmd/.ps1 ではなく既存 adapter が解決する native codex.exe を使用する。既存 read-only / ignore rules / strict config / ephemeral / output schema 設定を維持する。
- path は Project-relative slash 表記へ正規化する。junction で Project 外へ抜けるケースもテストする。
- dirty repository、stale baseline、recipe変更は、ユーザーが状態を解決してから新しい依頼として prepare する。自動 stash/reset はしない。
- snapshot / task / approval 上限は scope を狭める。承認文章や必須要件を切り捨てない。
- receipt/worktree quota は既存16件のまま。保存結果を確認して管理者が明示的に整理する。
- worktree は変更の分離手段。信頼済み test recipe は JVM プロセスの権限で project code を実行し、任意コード実行 sandbox ではない。
- 新規作成・削除・リネーム、任意の test command、自動 merge/push、必須別モデルレビューを追加しない。
- 親の意味評価は人による確認と同等の保証ではない。将来は同じ patch-bound 評価レコードへ独立 evaluator を接続できるが、MVP ではモデルを増設しない。

## 調査と開発記録

2026-10-09 の origin/main は `462b8ee1`、依頼基準は `db232aef`。差分20ファイルの主な変更は durable Goal wait と Checkpoint Resume であり、要件付き公開 implementation Tool は存在しなかった。既存 ExternalAgentDelegationService / IsolatedImplementationService / CodexExternalAgentExecutor、source snapshot、proposal apply、SelfPatchReview、receipt、ToolApprovalRepository、Run budget/cancellation を再利用した。Scheduler の lease は再実行可能な用途なので流用せず、同じ SQLite conditional UPDATE パターンによる永久 claim を使用する。

Phase 1 は DTO/validator/hash、Phase 2 は SQLite claim と認可、Phase 3 は既存 worktree への接続・scope manifest、Phase 4 は patch-bound acceptance、Phase 5 は公開 callback とコマンド、Phase 6 は回帰・Windows/live検証・文書化。初期 Red は未実装 API の compile failure、後の review 修正では intent 誤認・draft未保存・UNKNOWN照合・同名 callback 認可回避を assertion failure で再現し、Green を確認した。

Windows JDK25 で匿名 fixture の実 Codex E2E `LiveEnvironmentE2ETest#codexRequirementDrivenImplementation` を実行し、1件成功・失敗0・skip0（28.62秒）。有料モデルの通常単体テスト実行は行わず、live-e2e Profile と明示 environment opt-in に限定する。

## 必須ケースの検証対応

| 要求 | 主な検証 |
| --- | --- |
| 1〜5 構造化・自動許可・明示承認・要件伝達・保存 | ImplementationSpecificationTest / ImplementationRequestServiceTest / SpecificationDelegationIntegrationTest |
| 6〜8 承認捏造・引数改変・リポジトリ命令 | ImplementationRequestServiceTest / ImplementationCallbackBoundaryTest |
| 9 Project・Session・root | ImplementationRequestServiceTest / IsolatedImplementationServiceTest |
| 10〜12 範囲外・未対応操作・opt-in | SpecificationDelegationIntegrationTest / ImplementationSpecificationTest / ImplementationProposalTest / ImplementationRequestServiceTest |
| 13〜14 重複・原子的 claim | ImplementationRequestRepositoryTest / ImplementationRequestServiceTest の concurrent service case |
| 15〜16 取消・タイムアウト | SpecificationDelegationIntegrationTest の cancellation / provider timeout / administrator test timeout と既存 process / SelfPatchReview tests |
| 17〜19 再起動・UNKNOWN・再承認 | ImplementationRequestServiceTest / ImplementationRequestRepositoryTest |
| 20〜25 未確認・成功申告・証拠不足・失敗区別・保持・別パッチ | AcceptanceEvaluationTest / SpecificationDelegationIntegrationTest / ImplementationRequestServiceTest |
| 26〜28 構文・不足要件・共通接続 | ImplementationCommandTest / ImplementationToolsTest / SpecificationDelegationIntegrationTest / slash target substitution case |
| 29〜30 review/fix・既存マージ | 既存 ExternalAgent / Codex / Claude tests と ImplementationDelegationIntegrationTest / IsolatedImplementationServiceTest |

追加の検証は同名の任意 callback による認可迂回、人の拒否後の Policy 変更、承認期限切れ、Windows junction、terminal receipt 保存競合、スラッシュ対象差し替えを含む。意味評価の evidence は親 LLM の観察であり、客観的 test を装わない。

## 最終ローカル検証

2026-10-09、Windows/JDK25 の `./mvnw.cmd test -B -Pfull` は4,120件・失敗0・エラー0・skip1で成功（11分7秒）。skip は既存 DocumentRendererProcessTest の実 PlantUML/Png 検証。全回帰中/後の最終レビュー修正を含む最終コードでは、関連11クラス54件を再実行し、失敗0・エラー0・skip0を確認した。新規7クラスの通常ケースは36件、追加の実 Codex live ケースは1件成功した。

既に人の承認を求めた依頼は、その後 Policy が自動許可になっても明示承認を省略しない。レシピは保存済み envelope から取得し、隔離サービス側でも現行管理者設定と照合する。レビュー依頼中の implementation という名詞、Markdown 引用/コードを実装依頼として扱わない。明示実装の検出は保守的で、対応しない自然言語表現の場合はユーザーに明示的な依頼として確認する。

最終 GitHub CI は最終ブランチに対して全回帰を実行する。必須 CI・レビュー条件の成功確認前にはマージせず、保護の迂回、force push、admin merge を使わない。

## 不足要件の複数ターン確認

`prepareCodexImplementation` は specification が null の場合も `NEEDS_CLARIFICATION` の draft ID を保存する。親は質問前にこの ID を取得し、ユーザーの具体的な回答または確認後、`clarificationRequestId` と完成した specification を渡す。初回 `/agent codex implement <target>` も同じ draft を返す。元の slash target はモデルの引数とは独立して保存し、回答後も差し替えを拒否する。

同一 Project/root/session、15分以内の draft、現在の人の確認入力を必須とし、引用だけの回答や review/説明は確認に使わない。期限切れは新たな明示依頼が必要。originUserMessageId に server が生成する draft ID と確認 Run/input digest を記録する。draft 自体は実行・承認を開始しない。UNKNOWN 再試行の previousRequestId は draft を経由しても引き継ぎ、新規IDと明示承認を必須とする。未確認の自然言語表現は保守的に拒否する。

追加の回帰ケースは、自然言語の継続確認、slash target 保持、UNKNOWN 再承認、期限・Session・引用拒否、実 slash draft → 同じ隔離エンジンへの接続を含む。
不足要件の継続修正後、関連8クラス44件を再実行し、失敗0・エラー0・skip0を確認した。実 slash draft から新しい人の回答 Run を経て隔離実装に接続するケースを含む。
