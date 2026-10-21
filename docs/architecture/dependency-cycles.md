# 循環依存

分析対象コミット: `bb4f49247370ed3e6cd9cc3ee06209aa060e5075`。再実行方法は [README](README.md)。本番ソースSHA-256: `f62bb18b627dc42fbe5dd660c2f0d77494a2803076390bf44a74a9733df49ab5`。

以下は強連結成分（SCC）。SCC全体が一本の単純な循環を意味するわけではありません。2点相互参照と3点以上の経路を同じ到達可能性で検出します。ゲートはSCC名ではなく、循環に参加する個々のクラス辺を固定します。既存SCC内に追加する辺も失敗します。

## feature / 処理本体のみ — 1 SCC

### SCC 1（73境界）

`(root)`, `activity`, `application.input`, `application.read`, `application.run`, `application.session`, `application.state`, `artifact`, `attention`, `bluesky`, `briefing`, `chatgpt`, `checkpoint`, `computeruse`, `conversation`, `core`, `core.actionplan`, `core.chat`, `core.checkpoint`, `core.command`, `core.completion`, `core.contextbudget`, `core.dependency`, `core.diagnostic`, `core.execution`, `core.filesummary`, `core.policy`, `core.process`, `core.project`, `core.recentchanges`, `core.relatedgraph`, `core.service`, `core.stagnation`, `core.taskstate`, `core.working`, `doctor`, `dot`, `episode`, `event`, `externalagent`, `feed`, `github`, `goal`, `google`, `googlecalendar`, `http`, `image`, `interest`, `llm`, `mcp`, `memory`, `paper`, `reflection`, `reminder`, `review`, `search`, `skills`, `slack`, `sound`, `storage`, `subagent`, `summarize`, `task`, `temporal`, `timing`, `topic`, `ui`, `urlfetch`, `vectordocument`, `vectorstore`, `voice`, `websearch`, `workcontext`

各方向の全代表クラスと証拠は [cycle-edges.tsv](data/cycle-edges.tsv)。以下は先頭8方向です。

| 方向 | 分類 | 代表的な依存元 → 依存先（証拠） |
| --- | --- | --- |
| (root) → core.project | business | Method &lt;dev.mikoto2000.rei.StartupOptions.parse([Ljava.lang.String;, java.nio.file.Path)&gt; calls method &lt;dev.mikoto2000.rei.core.project.ProjectService.resolveExistingDirectory(java.nio.file.Path, java.lang.String)&gt; in (StartupOptions.java:55) |
| (root) → event | business | Method &lt;dev.mikoto2000.rei.ApplicationShutdownNotifier.begin(java.lang.String)&gt; calls method &lt;dev.mikoto2000.rei.event.AgentEventBus.publish(dev.mikoto2000.rei.event.AgentEvent)&gt; in (ApplicationShutdownNotifier.java:43) |
| activity → computeruse | business | Method &lt;dev.mikoto2000.rei.activity.ActivityCapture.analyze(dev.mikoto2000.rei.activity.ActivityCapture$Frame)&gt; calls method &lt;dev.mikoto2000.rei.computeruse.CapturedScreen.displays()&gt; in (ActivityCapture.java:424) |
| activity → core.chat | business | Method &lt;dev.mikoto2000.rei.activity.ActivityQualityEvaluation.evaluate(java.util.List)&gt; calls method &lt;dev.mikoto2000.rei.core.chat.RunCancellation.propagate(java.lang.Throwable)&gt; in (ActivityQualityEvaluation.java:190) |
| activity → core.project | business | Method &lt;dev.mikoto2000.rei.activity.ActivityCommand$ContextCommand.call()&gt; calls method &lt;dev.mikoto2000.rei.core.project.ProjectContext.id()&gt; in (ActivityCommand.java:620) |
| activity → event | business | Method &lt;dev.mikoto2000.rei.activity.ActivityAgentEvidenceSource.onEvent(dev.mikoto2000.rei.event.AgentEvent)&gt; calls method &lt;dev.mikoto2000.rei.event.AgentEvent.id()&gt; in (ActivityAgentEvidenceSource.java:71) |
| activity → llm | business | Method &lt;dev.mikoto2000.rei.activity.LlmDailySummaryWriter.request(dev.mikoto2000.rei.activity.DailySummaryAggregate, java.lang.String)&gt; calls method &lt;dev.mikoto2000.rei.llm.OutputLimitDetector.isOutputLimitReached(org.springframework.ai.chat.model.ChatResponse)&gt; in (LlmDailySummaryWriter.java:182) |
| activity → memory | business | Method &lt;dev.mikoto2000.rei.activity.ClassificationRuleSuggestions.suggest(boolean)&gt; calls constructor &lt;dev.mikoto2000.rei.memory.util.SensitiveInfoDetector.&lt;init&gt;()&gt; in (ClassificationRuleSuggestions.java:137) |
## feature / 配線を含む全依存 — 1 SCC

### SCC 1（79境界）

`(root)`, `activity`, `application.input`, `application.project`, `application.read`, `application.run`, `application.session`, `application.state`, `application.task`, `artifact`, `attention`, `bluesky`, `briefing`, `chatgpt`, `checkpoint`, `computeruse`, `conversation`, `core`, `core.actionplan`, `core.chat`, `core.checkpoint`, `core.command`, `core.completion`, `core.configuration`, `core.contextbudget`, `core.dependency`, `core.diagnostic`, `core.execution`, `core.filesummary`, `core.policy`, `core.process`, `core.project`, `core.recentchanges`, `core.relatedgraph`, `core.service`, `core.sqlitevec`, `core.stagnation`, `core.taskstate`, `core.working`, `doctor`, `dot`, `episode`, `event`, `externalagent`, `feed`, `github`, `goal`, `google`, `googlecalendar`, `http`, `image`, `interest`, `llm`, `mcp`, `memory`, `paper`, `planning`, `reflection`, `reminder`, `review`, `search`, `skills`, `slack`, `sound`, `storage`, `subagent`, `summarize`, `task`, `temporal`, `timing`, `topic`, `ui`, `urlfetch`, `vectordocument`, `vectorstore`, `voice`, `web`, `websearch`, `workcontext`

各方向の全代表クラスと証拠は [cycle-edges.tsv](data/cycle-edges.tsv)。以下は先頭8方向です。

| 方向 | 分類 | 代表的な依存元 → 依存先（証拠） |
| --- | --- | --- |
| (root) → application.input | wiring | Method &lt;dev.mikoto2000.rei.ReiApplication.runShell([Ljava.lang.String;, dev.mikoto2000.rei.core.project.ProjectClient)&gt; calls method &lt;dev.mikoto2000.rei.application.input.ConversationInput.text()&gt; in (ReiApplication.java:379) |
| (root) → application.run | wiring | Method &lt;dev.mikoto2000.rei.ReiApplication.approvalRunActive(java.lang.String)&gt; calls method &lt;dev.mikoto2000.rei.application.run.RunRegistry.get(java.lang.String)&gt; in (ReiApplication.java:500) |
| (root) → attention | wiring | Method &lt;dev.mikoto2000.rei.ReiApplication.configureCommandOutput(picocli.CommandLine, org.jline.terminal.Terminal)&gt; calls method &lt;dev.mikoto2000.rei.attention.AttentionCommand.setShellOutput(java.io.PrintWriter)&gt; in (ReiApplication.java:597) |
| (root) → chatgpt | wiring | Method &lt;dev.mikoto2000.rei.ReiApplication.configureCommandOutput(picocli.CommandLine, org.jline.terminal.Terminal)&gt; calls method &lt;dev.mikoto2000.rei.chatgpt.ChatGptCommand.setShellOutput(java.io.PrintWriter)&gt; in (ReiApplication.java:551) |
| (root) → checkpoint | wiring | Method &lt;dev.mikoto2000.rei.ReiApplication.configureCommandOutput(picocli.CommandLine, org.jline.terminal.Terminal)&gt; calls method &lt;dev.mikoto2000.rei.checkpoint.CheckpointCommand.setShellOutput(java.io.PrintWriter)&gt; in (ReiApplication.java:574) |
| (root) → core.chat | wiring | Field &lt;dev.mikoto2000.rei.ReiApplication.activeRuns&gt; has type &lt;dev.mikoto2000.rei.core.chat.ConversationInputRouter&gt; in (ReiApplication.java:0) |
| (root) → core.command | wiring | Constructor &lt;dev.mikoto2000.rei.ReiApplication.&lt;init&gt;(dev.mikoto2000.rei.ui.shell.RootCommand, picocli.CommandLine$IFactory, dev.mikoto2000.rei.core.service.ModelHolderService, dev.mikoto2000.rei.EscCancellationMonitor, dev.mikoto2000.rei.core.service.CommandCancellationService, dev.mikoto2000.rei.core.service.CommandCompletionNotificationPolicy, dev.mikoto2000.rei.core.service.CommandUserInputDisplayPolicy, dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService, dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService, dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator, dev.mikoto2000.rei.event.AgentEventBus, dev.mikoto2000.rei.topic.AgentActivityTracker, org.springframework.core.env.Environment)&gt; calls method &lt;dev.mikoto2000.rei.core.command.ReiLineReaderFactory.completionEngine()&gt; in (ReiApplication.java:66) |
| (root) → core.completion | wiring | Method &lt;dev.mikoto2000.rei.ReiApplication.runShell([Ljava.lang.String;, dev.mikoto2000.rei.core.project.ProjectClient)&gt; calls constructor &lt;dev.mikoto2000.rei.core.completion.ApprovalActionMenu.&lt;init&gt;(org.jline.reader.LineReader, java.util.function.Supplier)&gt; in (ReiApplication.java:332) |
## package / 処理本体のみ — 1 SCC

### SCC 1（88境界）

`activity`, `activity.behavior`, `application.input`, `application.read`, `application.run`, `application.session`, `application.state`, `artifact`, `attention`, `bluesky`, `bluesky.command`, `briefing`, `briefing.command`, `chatgpt`, `checkpoint`, `computeruse`, `conversation`, `core`, `core.actionplan`, `core.chat`, `core.checkpoint`, `core.command`, `core.completion`, `core.contextbudget`, `core.dependency`, `core.diagnostic`, `core.execution`, `core.filesummary`, `core.policy`, `core.process`, `core.project`, `core.recentchanges`, `core.relatedgraph`, `core.service`, `core.stagnation`, `core.taskstate`, `core.working`, `dev.mikoto2000.rei`, `doctor`, `dot`, `episode`, `event`, `externalagent`, `feed`, `feed.command`, `github`, `goal`, `google`, `googlecalendar`, `googlecalendar.command`, `http`, `http.cache`, `image`, `image.command`, `interest`, `interest.command`, `llm`, `llm.capture`, `mcp`, `memory.command`, `memory.service`, `paper`, `reflection`, `reminder`, `reminder.command`, `review`, `search`, `skills`, `skills.command`, `slack`, `sound`, `storage`, `subagent`, `summarize`, `summarize.command`, `task`, `task.command`, `temporal`, `timing`, `topic`, `ui.shell`, `ui.shell.sound`, `urlfetch`, `vectordocument`, `vectorstore`, `voice`, `websearch`, `workcontext`

各方向の全代表クラスと証拠は [cycle-edges.tsv](data/cycle-edges.tsv)。以下は先頭8方向です。

| 方向 | 分類 | 代表的な依存元 → 依存先（証拠） |
| --- | --- | --- |
| activity → activity.behavior | business | Method &lt;dev.mikoto2000.rei.activity.ActivityCommand$BehaviorCommand.call()&gt; calls method &lt;dev.mikoto2000.rei.activity.behavior.BehaviorAssessment.continuousEntertainmentSeconds()&gt; in (ActivityCommand.java:708) |
| activity → computeruse | business | Method &lt;dev.mikoto2000.rei.activity.ActivityCapture.analyze(dev.mikoto2000.rei.activity.ActivityCapture$Frame)&gt; calls method &lt;dev.mikoto2000.rei.computeruse.CapturedScreen.displays()&gt; in (ActivityCapture.java:424) |
| activity → core.chat | business | Method &lt;dev.mikoto2000.rei.activity.ActivityQualityEvaluation.evaluate(java.util.List)&gt; calls method &lt;dev.mikoto2000.rei.core.chat.RunCancellation.propagate(java.lang.Throwable)&gt; in (ActivityQualityEvaluation.java:190) |
| activity → core.project | business | Method &lt;dev.mikoto2000.rei.activity.ActivityCommand$ContextCommand.call()&gt; calls method &lt;dev.mikoto2000.rei.core.project.ProjectContext.id()&gt; in (ActivityCommand.java:620) |
| activity → event | business | Method &lt;dev.mikoto2000.rei.activity.ActivityAgentEvidenceSource.onEvent(dev.mikoto2000.rei.event.AgentEvent)&gt; calls method &lt;dev.mikoto2000.rei.event.AgentEvent.id()&gt; in (ActivityAgentEvidenceSource.java:71) |
| activity → llm | business | Method &lt;dev.mikoto2000.rei.activity.LlmDailySummaryWriter.request(dev.mikoto2000.rei.activity.DailySummaryAggregate, java.lang.String)&gt; calls method &lt;dev.mikoto2000.rei.llm.OutputLimitDetector.isOutputLimitReached(org.springframework.ai.chat.model.ChatResponse)&gt; in (LlmDailySummaryWriter.java:182) |
| activity → topic | business | Method &lt;dev.mikoto2000.rei.activity.AutomaticPeriodCoaching.allowed()&gt; calls method &lt;dev.mikoto2000.rei.topic.AgentActivityTracker.isAgentBusy()&gt; in (AutomaticPeriodCoaching.java:101) |
| activity → workcontext | business | Method &lt;dev.mikoto2000.rei.activity.ActivityObservationContextSource.capture(dev.mikoto2000.rei.core.project.ProjectContext, java.time.Instant)&gt; calls method &lt;dev.mikoto2000.rei.workcontext.WorkContext.items()&gt; in (ActivityObservationContextSource.java:95) |
## package / 配線を含む全依存 — 1 SCC

### SCC 1（94境界）

`activity`, `activity.behavior`, `application.input`, `application.project`, `application.read`, `application.run`, `application.session`, `application.state`, `application.task`, `artifact`, `attention`, `bluesky`, `bluesky.command`, `briefing`, `briefing.command`, `chatgpt`, `checkpoint`, `computeruse`, `conversation`, `core`, `core.actionplan`, `core.chat`, `core.checkpoint`, `core.command`, `core.completion`, `core.configuration`, `core.contextbudget`, `core.dependency`, `core.diagnostic`, `core.execution`, `core.filesummary`, `core.policy`, `core.process`, `core.project`, `core.recentchanges`, `core.relatedgraph`, `core.service`, `core.sqlitevec`, `core.stagnation`, `core.taskstate`, `core.working`, `dev.mikoto2000.rei`, `doctor`, `dot`, `episode`, `event`, `externalagent`, `feed`, `feed.command`, `github`, `goal`, `google`, `googlecalendar`, `googlecalendar.command`, `http`, `http.cache`, `image`, `image.command`, `interest`, `interest.command`, `llm`, `llm.capture`, `mcp`, `memory.command`, `memory.service`, `paper`, `planning`, `reflection`, `reminder`, `reminder.command`, `review`, `search`, `skills`, `skills.command`, `slack`, `sound`, `storage`, `subagent`, `summarize`, `summarize.command`, `task`, `task.command`, `temporal`, `timing`, `topic`, `ui.shell`, `ui.shell.sound`, `urlfetch`, `vectordocument`, `vectorstore`, `voice`, `web`, `websearch`, `workcontext`

各方向の全代表クラスと証拠は [cycle-edges.tsv](data/cycle-edges.tsv)。以下は先頭8方向です。

| 方向 | 分類 | 代表的な依存元 → 依存先（証拠） |
| --- | --- | --- |
| activity → activity.behavior | business | Method &lt;dev.mikoto2000.rei.activity.ActivityCommand$BehaviorCommand.call()&gt; calls method &lt;dev.mikoto2000.rei.activity.behavior.BehaviorAssessment.continuousEntertainmentSeconds()&gt; in (ActivityCommand.java:708) |
| activity → computeruse | business | Method &lt;dev.mikoto2000.rei.activity.ActivityCapture.analyze(dev.mikoto2000.rei.activity.ActivityCapture$Frame)&gt; calls method &lt;dev.mikoto2000.rei.computeruse.CapturedScreen.displays()&gt; in (ActivityCapture.java:424) |
| activity → core.chat | business | Method &lt;dev.mikoto2000.rei.activity.ActivityQualityEvaluation.evaluate(java.util.List)&gt; calls method &lt;dev.mikoto2000.rei.core.chat.RunCancellation.propagate(java.lang.Throwable)&gt; in (ActivityQualityEvaluation.java:190) |
| activity → core.project | business | Method &lt;dev.mikoto2000.rei.activity.ActivityCommand$ContextCommand.call()&gt; calls method &lt;dev.mikoto2000.rei.core.project.ProjectContext.id()&gt; in (ActivityCommand.java:620) |
| activity → core.service | wiring | Method &lt;dev.mikoto2000.rei.activity.ActivityConfiguration.activityExtractor(dev.mikoto2000.rei.llm.LlmModelProvider, dev.mikoto2000.rei.core.service.ModelHolderService, dev.mikoto2000.rei.activity.ActivityProperties)&gt; calls method &lt;dev.mikoto2000.rei.core.service.ModelHolderService.get()&gt; in (ActivityConfiguration.java:64) |
| activity → event | business | Method &lt;dev.mikoto2000.rei.activity.ActivityAgentEvidenceSource.onEvent(dev.mikoto2000.rei.event.AgentEvent)&gt; calls method &lt;dev.mikoto2000.rei.event.AgentEvent.id()&gt; in (ActivityAgentEvidenceSource.java:71) |
| activity → llm | wiring | Method &lt;dev.mikoto2000.rei.activity.ActivityConfiguration.activityExtractor(dev.mikoto2000.rei.llm.LlmModelProvider, dev.mikoto2000.rei.core.service.ModelHolderService, dev.mikoto2000.rei.activity.ActivityProperties)&gt; calls method &lt;dev.mikoto2000.rei.llm.LlmModelProvider.chatModel(java.lang.String)&gt; in (ActivityConfiguration.java:63) |
| activity → topic | business | Method &lt;dev.mikoto2000.rei.activity.AutomaticPeriodCoaching.allowed()&gt; calls method &lt;dev.mikoto2000.rei.topic.AgentActivityTracker.isAgentBusy()&gt; in (AutomaticPeriodCoaching.java:101) |

## 解釈と優先順位（分析・提案）

P1: AgentRunConfiguration / WebApiConfiguration / AiConfiguration の横断配線をbootstrapへ集める。P2: core直下のTools・アプリケーションサービスから各機能への呼び出しと、各機能からcoreのRun/Project/Policy型への逆参照を分ける。P3: Run・Sessionとconversationの受付・保存・実行境界を整理する。原因は共有コンテキストと実行・保存の相互協調にあるとの推定であり、循環であることだけで実行時障害とは判断しません。具体的な図と移行の完了条件は [README](README.md) と [移行計画](migration-roadmap.md)。
