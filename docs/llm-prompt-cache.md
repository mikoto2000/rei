# LLM Prompt Cache 計測と Prefix 安定性

## Phase 1: 調査と計測

基点は `6aeb5e242610b64d672101e925ba4e3c1dfffe13`。変更前は TimingStore が input/output と first framework chunk / first generation text を記録していたが、cache read は記録していなかった。Request Capture は Shell の明示的に対象になった root Run の SDK HTTP リクエストを既存の件数・容量制限で保存する。今回、新しい本文保存機構・DB テーブルは追加しない。

|経路|構築・実行箇所|構成・変動・所有権|
|---|---|---|
|通常チャット|AiConfiguration / LlmChatClientProvider / ChatExecutionService|SystemPromptService と各 Advisor、Session 履歴、User。ToolCallbacks は既存登録、RunExecutionContext / AgentRunContext を toolContext に渡す|
|Planning Loop|ChatExecutionService / StagnationChatModel|各反復の計画・完了判定指示は変動。既存予算・承認・独立検証を継続|
|Tool 継続|StagnationChatModel / ToolLoopSupport|元の Prompt と options を保持し Assistant tool_calls / Tool responses を追加。ContextAssembler が最終投影を作る|
|SubAgent / 並列|SubAgentService / BoundedToolLoop|役割・子依頼・限定 Tool を持つ独立 Prompt。親の Prompt は共有しない。子 ownership と共有 Model Budget は既存管理|
|Reflection|RunReflectionService / GoalReflectionService|イベント事実による振り返りで、追加 LLM 呼び出しはない。Prompt/cache 計測対象は発生しない|
|Skill Selection|AgentSkillImplicitSelector / SkillCandidateSelector|ルール、候補 metadata、出力形式、依頼。検索順位は score と name の決定的順序、semantic search は既存ランキング|
|Context Compaction|ContextAssembler / ConversationCompressor|履歴・Run 別に summary と throughSequence を管理。summary 生成も予算に算入|
|Working Set|WorkingSetAdvisor / ContextAssembler|初期注入に metadata rei.workingSet を付与、最終投影時は最新 renderForPrompt を再取得。アクセス日時・並び順も変動する|
|Request Capture|CapturingChatModel / CaptureStore / CaptureInterceptor|opt-in の HTTP attribution と保存。無効でも TimingStore 計測は可能|
|Codex / Claude Code 委譲|ExternalAgentDelegationService / 各 Executor|外部 CLI の独立経路。CodexTokenUsage は親予算の total accounting。内部 Prompt の cache/TTFT は見えず、この計測の対象外|
|Model Budget|RunExecutionContext / ModelCallBudgetScope / OutputLimitRunBudget|既存の reservation と usage accounting を変更しない|
|Provider|LlmModelProvider / FallbackChatModel|OpenAI SDK の互換 endpoint。TensorFold/vLLM 専用 cache adapter は存在しない。feature 単位で model instance を再利用、fallback は既存動作|

上記はコードで確認した事実。特定サーバーの Prefix Cache の有効化、保持時間、worker affinity、速度改善はこのコードだけでは確定できない。

### 利用方法

`/timing last --details` または `/timing RUN_ID --details`。既存の Project / Session 所有権検証、TTL、Run/Span 件数上限、無効化設定 (`rei.timing.enabled=false`) を再利用する。追加のディスク書き込みはない。

LLM Span の ID は既存 LLM requestId。feature、Provider プロトコル、応答 model、cachedInputTokens、Prefix metadata を追加。Provider が OpenAI SDK CompletionUsage を返した場合は `openai-compatible` と表示し、TensorFold/vLLM/OpenAI 本体を推測しない。特定 endpoint 名を記録しない。model は応答 metadata の値のみ、未提供なら UNKNOWN。安全な短い識別子以外も UNKNOWN にする。

Spring AI 2.0.1 の `Usage.getCacheReadInputTokens()` は OpenAI `prompt_tokens_details.cached_tokens` を既に正規化する。Provider 非依存の Usage implementation も同 API を使用する。未提供を 0 に変換しない。負値・input より大きい cache count は UNKNOWN。入力が不明なら hit rate / uncached は UNKNOWN。JSON/API レコードでは null、表示 Aggregate の null も UNKNOWN を意味する。

Run の入力合計は入力が取得できた呼び出しだけを合計。cache hit rate は input と cached の両方がある呼び出しの `sum(cached)/sum(input)`。UNKNOWN の呼び出しは分母・分子から除外し、取得件数を別に示す。Provider/model、feature の内訳と duration / TTFT の min・mean・median・max を表示。Run 全体の経過時間は既存値。並列 call duration の加算を Run 全体時間と解釈しない。

TTFT は最初の非空生成テキストまでの framework 観測値で、サーバー内 token 発生時刻ではない。同期 call では未取得。prefill は Provider が報告する値を現行 API で取得できないため未取得。SDK 内部 retry および FallbackChatModel の内部試行は現行の wrapper では個別に見えず、論理呼び出しの合計 duration と最終応答 usage になる。失敗・取消・欠落 Span は既存 status / incomplete と合わせて確認する。

### Prefix 診断

Run ごとに SecureRandom の256-bit鍵を生成し HMAC-SHA256 を使用。鍵・本文・Tool名・schema 本文・ファイルパスを保存しない。ストア破棄時に鍵も破棄され、Run 間でハッシュを比較できない。領域は ToolDefinitions の別 API field と、送信メッセージ順の SYSTEM / USER / ASSISTANT / TOOL / WORKING_SET / RUN_OBSERVATIONS / CONTEXT_SUMMARY。各領域の順序、文字数、hash、前回との差、最初の変更を保存。初回 changed は UNKNOWN。最大64領域、それ以降は truncated。文字数は token 数ではない。画像・音声バイト列の完全な比較は未対応。System 内に結合済みの Skill / Developer は分割推定しない。

既存 TimingStore の max-runs (100)、max-spans-per-run (2000)、max-total-spans (10000)、TTL (1h) を共有。各 Span 最大64領域、ハッシュ処理の UTF-8 一時配列は4096文字ずつ。キャッシュ診断失敗は TimingExecution.safely が隔離し、LLM 呼び出しを失敗させない。本文は処理中にのみ参照し、保存しない。

領域一致から Provider の token cache hit を断定しない。Tool API field と messages の物理 token 化順序も推測しない。実測 cache count と構造診断は独立。

### ベースライン

実 Provider の比較実験は未実施。変更前に取得できる cached / uncached / prefill は UNKNOWN。変更前の構造では Run summary が固定 System より前、Skill の User request が候補より前。これを回帰テストで再現する。Provider の実際の cache hit・TTFT 改善率は報告しない。

参考: [OpenAI の cached_tokens の説明](https://developers.openai.com/cookbook/examples/prompt_caching101)、[vLLM の Automatic Prefix Caching](https://docs.vllm.ai/en/v0.15.0/features/automatic_prefix_caching/)。Prefix の構造改善とサーバーによる実際の再利用は区別する。

## Phase 2: 固定 Prefix

ContextAssembler は元の先頭 System メッセージ群を保持し、その後に Run observations を挿入する。元のメッセージ相互の順序、System/User の role、historical data の注意文を維持。Tool definitions は別 API field のまま。

AgentSkillImplicitSelector はルール → 候補 metadata → Return format → User request の順にする。repository 全件の場合は name 順、検索が提供した候補の場合は既存 score 順をそのまま維持する。選択・解析・fallback・予算処理は変更しない。

StableToolDefinitions は既存の StagnationChatModel / BoundedToolLoop の初期準備で supplied ToolCallbacks のみを name 順へ、JSON Schema の object keys を再帰的に昇順へ。array (required、enum、oneOf 等) の順序を維持。name/description の内容、ToolMetadata、ToolContext、call を元 callback に委譲。schema が解析できなければ元定義で継続。Stagnation では実行時権限・イベント判定用ラッパーを構築した後で定義を安定化し、domain callback の型による既存判定も保持する。追加 Tool Profile は導入せず、既存 EXCLUSIVE / READ_ONLY / CONVERSATION と子 Agent の Tool 限定を再利用する。

Red 記録: ContextAssemblerTest 1件と AgentSkillImplicitSelectorTest 2件が意図した順序差で失敗 (計20件中3 failures)。Tool 定義テストは未実装 StableToolDefinitions により compile failure。Green は同じテストを再実行する。元の schema/options/messages を変更しない回帰も追加。

## Phase 3: Run 内の保持と実装判断

Prompt Context Snapshot は新しい永続オブジェクトを導入しない。既存 StagnationChatModel / BoundedToolLoop が最初の Prompt と options/callbacks を保持し、Tool結果・介入を末尾に追記する仕組みを再利用する。Phase 2 の定義 canonicalization も初期準備時の1回だけ。親・子の文脈や権限を共有しない。Planning Loop が新しい指示で構築した Prompt は引き続き最新状態を使う。policy の判断は Tool 実行のたびに既存 guard に委譲する。

ContextAssembler / ConversationSummaryRepository は既に Frozen Summary (`summary`, `throughSequence`)、Incremental Context (cursor より新しい entries)、Checkpoint Compaction (threshold を超えたときのみ summary 再構築)、hard limit と summary 予算を持つ。rollingSummarySeesOnlyPreviousSummaryAndNewPrefix、runObservationsFollowOriginalSystemPrefixAndSummaryStaysFrozen および既存圧縮回帰で確認。圧縮閾値を hit rate に合わせて変更しない。

Working Set の差分追記は今回は実装しない。FileReference は path / lastAccessedAt / lastModifiedAt / accessType のみで、revision・move tombstone・権限 revision を持たない。renderForPrompt の更新は現在の Tool loop の状態鮮度に必要。根拠なく初期状態を固定すると削除・移動・権限変更が古いまま残る可能性がある。実 Provider の uncached/TTFT の改善量も未計測。新しいイベント履歴・永続化・要約統合を足すコストと安全性を正当化できないため、現行の最新状態再投影を維持し、WORKING_SET 領域変化を診断する。

updatedWorkingSetAndNewSystemPolicyAreUsedWithoutStaleSnapshot は最終投影が元の raw Prompt を変更せず、最新 supplier と新しい System を使用することを検証する。これはファイル移動や revision 機構の実装テストではない。新しい Snapshot キャッシュ自体を設けないため、古い Tool Profile・Security Policy・model をそこから復元する経路は存在しない。Checkpoint Resume の System 再生成と予算復元は変更せず、既存回帰で検証する。



子 Agent は既存 SUBAGENT_STARTED / COMPLETED / FAILED イベントで独立 Timing Run を開始・終了する。通常・並列の子 ownership を保持し、taskSummary は保存しない。子と親の集計を混ぜない。子は自身の subagent:RUN_ID Session 所有権で recorder.snapshot を照会できる。親 Session の /timing 表示に子の usage を合算する UI は今回追加しない。未開始、Project 所有権のない standalone 子や外部 CLI/OAuth backend の内部 usage は未計測になり得る。

