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


## Phase 4: Run 間・Provider の評価

ContextHistoryAdvisor は過去の会話ログ・summary cursor を既に再利用する。ただし user/assistant 最終メッセージと prior Run observations を再構成するため、前の Run の全 Tool transcript とバイト単位で一致するとは限らない。会話意味・権限・予算を変えずに完全 Prefix を保証するには transcript の複製保存が必要になり得る。今回は本文複製・古い System の持越しを追加しない。Phase 2 の固定 System と定義は Run 間でも同じ入力集合なら決定的。

LlmModelProvider の feature model は ConcurrentHashMap で再利用され、既に同じ設定の endpoint/model を継続使用する。サーバー側の worker 分配、KV Cache の保持単位や TTL はクライアントから未確認。TensorFold/vLLM で cache count が返れば OpenAI 互換 Usage として計測でき、返らなければ UNKNOWN。OpenAI SDK が持つ promptCacheKey を無条件に送ったり、未知 Provider に cache_control を注入したりしない。現行 fallback、モデル能力・利用可能性、ポリシー、Model Budget を優先する。

自動ルーティング、キャッシュキー生成、圧縮閾値調整は実装を見送る。実 Provider の効果が未実測で、feature ごとの異なる能力要件を変える根拠がない。今回の診断と既存設定で endpoint/model/threshold を比較評価できる段階に留める。Provider 固有の control の実装には、管理者が明示する capability とサーバー検証を追加する必要がある。

### 再現可能な性能評価

`./mvnw.cmd -B -Pfull -Dtest=PrefixDiagnosticScenariosTest test`。同じ固定 System (約9Ki文字)、Tool領域 (約3.5Ki文字)、変動履歴を使う合成 fixture。20回 warmup 後101回の `PrefixDiagnostics.observe` を測定。実アプリのファイル読取/書込、Tool実行、Provider通信は発生しない。source 構築コストと Run全体のオーバーヘッドを測った値ではない。

2026-10-11 Windows / OpenJDK 25、ローカル実行結果:

|fixture|診断中央値 µs|最小 µs|最大 µs|
|---|---:|---:|---:|
|通常チャット|36.7|23.6|557.6|
|連続 Session|29.4|22.8|95.8|
|Tool 継続|40.5|21.7|154.4|
|ファイル読取型|37.3|21.3|97.2|
|ファイル更新型|26.4|20.5|81.8|
|Planning 型|42.4|31.9|94.5|
|長い圧縮型 (約65Ki文字追加履歴)|120.0|73.9|227.2|
|Skill Selection 型|37.0|22.8|85.4|

変更前にこの診断処理は存在しなかった。上の数値は追加処理のコストで、LLM latency 削減の値ではない。メモリは件数・領域・文字列フィールド長で保持を制限するが、プロセス RSS/heap の比較測定は未実施。

|比較指標|変更前|変更後|
|---|---|---|
|cache_hit_rate|未計測|取得可能な Provider 応答を正規化、実 Provider 比較は未計測|
|uncached_input_tokens|未計測|取得済み分だけ計算、実 Provider 比較は未計測|
|TTFT|既存 framework timing|既存観測を再利用、比較は未計測|
|prefill_duration|未計測|未計測|
|Run 全体実行時間|既存 timing|既存 timing、比較は未計測|
|入力総数 / LLM Call 回数|既存 usage / span|cache 集計に取得件数を併記、実 Provider 比較は未計測|
|Run summary 後の固定 System 位置|summary より後|先頭 System を保持 (回帰検証)|
|Skill metadata と依頼の順序|依頼 → metadata|metadata → 依頼 (通常・予算付き経路検証)|
|同一集合の Tool/schema|登録順・object key 順に依存|名前/object key を canonical 化、実行・配列順は保持|

実環境比較は同じモデル・endpoint・temperature・出力予算・入力で各シナリオを複数回実行し、`/timing` から input/cache の取得率と重み付き rate、uncached、最初の生成テキストまでの時間、duration と Run elapsed の中央値・範囲を比較する。cold/warm、保持時間、worker 分配を分け、response quality と Run成功率も確認する。UNKNOWN による選別バイアス、fallback、同時呼び出し、Span省略を記録する。本文保存が必要な場合のみ既存 Request Capture を明示的に使う。

### 既知の制限と今後

- cache/usage 計測の対応は実際の Usage 欠落状況次第。UNKNOWN は cache miss を意味しない。
- 記録された Run/Span のみを集計し、上限・TTL で欠落した記録は復元しない。親の統計に独立した子の usage を自動合算しない。
- SDK内部 retry と endpoint fallback の物理試行単位の usage は未分離。prefill、KV Cache worker ID、retention は未取得。
- 診断は最大64領域の text / tool call / tool response と ToolDefinitions。media バイト列の比較、結合済み Developer/Skill の詳細分割、token 単位の共通 Prefix 数は未実装。
- Working Set revision/delta/move tombstone、Run間 Tool transcript 再送、自動 cache tuning は今後の検討。今回実装済みと混同しない。


最終レビューでは小数 schema constraint の精度と ToolContext のない同期補助呼び出しを追加テストで再現した。独立 JUnit 実行で2件 Red → BigDecimal/BigInteger 読取と既存 AgentRunScope fallback により2件 Green。ToolContext の明示的 ownership を最優先にし、同期 lexical scope は補助呼び出しだけの fallback とする。全回帰とは別出力先で実行し、既存テスト JVM の classes は書き換えない。詳細表示に call ごとの uncached_input_tokens / cache_hit_rate / usage_supported / cache_metrics_supported / prefill_duration=UNKNOWN も併記する。

静的解析: 確定した作業基点から変更した本番 Java 全クラスを `javac -Xlint:all -proc:none` で別出力先へコンパイルし成功。既存 BoundedToolLoop / StagnationChatModel の serialVersionUID と lexical scope に関する5警告は残る。今回の新規クラスに警告はない。`git diff --check` も成功。

実 Provider の到達確認: 構成された chat endpoint の `/v1/models` を5秒 deadline で照会したが TaskCanceledException (timeout) で取得できなかった。秘密値・catalog本文は出力せず、生成 API は呼んでいない。この環境から実 Provider の cold/warm 比較ができたとは報告しない。

## 検証記録 (2026-10-11)

- CI と同じ `.github/ci/full-test.ps1 -TimeoutSeconds 1200` の全回帰: 5033件、failures 0、errors 0、skipped 2、15分48秒。最終レビュー修正前の構築済み classes に対する検証。
- 作業中に main の PR #99 が入ったため rebase (競合なし)。最終修正と最新 main を含む targeted regression: 340件、failures/errors/skipped 0、1分21秒。LLM、Timing、Skill Selection、Model Budget、ToolPermission、Capture、Checkpoint、SubAgent (追加された自動 Plan 委譲テストも含む)。
- 最終状態の全回帰は GitHub PR CI で確認する。ローカルで最終状態の全 suite を再実行したとは扱わない。
- 既存 TimingOverheadProbeTest (小さい固定 Prompt、7 batch ×1000 call の中央値): timing disabled 4.809µs/call、enabled 13.711µs/call、差 8.902µs/call。元出力・イベント3000件・Span1000件の保持を検証。今回の診断を含む timing 全体と無効状態の比較であり、変更前の timing 実装との差ではない。
- TimingMemoryProbeTest: 10000基本 Span の post-GC process heap delta 約3.32MB。この probe は Prefix 領域を投入しないため、新しい Prefix metadata を満杯まで保持したメモリ量の測定ではない。
- 合成 Prefix 診断の最終再測定中央値: chat 13.6µs、session 13.9µs、tool 18.8µs、read 13.3µs、write 13.4µs、planning 13.3µs、compaction 66.4µs、skill 16.3µs。前の測定との差は JVM warmup / 並行負荷等の変動を含み、コード改善率とは解釈しない。
- System/User の role と元指示、schema の array 順・正確な小数、callback 実行・ToolContext、実行時権限、Shared Model Budget、summary cursor / hard limit / Resume を既存回帰で確認。診断本文・鍵・taskSummary を保存しない assertion、所有 Project/Session の照会境界、容量・eviction のテストを追加。

最終投影の Working Set metadata 保持も追加検証: `rei.workingSet` が null になる Red (13件中1 failure) を再現し、本文再描画時に元 metadata を保持して WORKING_SET の分類が成立することを確認した。修正後の同じ対象回帰340件は failures/errors/skipped 0 (1分34秒)。本文・role・Token Budget は変えず、診断識別子を維持する修正。
