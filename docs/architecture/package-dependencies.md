# パッケージ依存の計測結果

分析対象コミット: `bb4f49247370ed3e6cd9cc3ee06209aa060e5075`。再実行方法は [README](README.md)。本番ソースSHA-256: `f62bb18b627dc42fbe5dd660c2f0d77494a2803076390bf44a74a9733df49ab5`。

バックエンド本番: **1560 Javaファイル / 106パッケージ / 2851 bytecodeクラス**。

ASTのtypesはネスト・匿名クラスを含む宣言数。bytecode数はLombok生成・匿名クラスを含み、ソース数とは一致しません。terminalの共有ソースはバックエンド側で一度数え、terminal独自ソースのみ別計上します。生成ソースは対象ディレクトリに実在するファイルのみです。

| 領域 | Javaファイル |
| --- | --- |
| backend production | 1560 |
| backend test | 1127 |
| terminal production | 15 |
| terminal test | 23 |

コンパイル対象Javaソース総数（backend + terminal独自、重複除去）: **2725**。生成.java: **0**。

別途、コンパイル対象外のテストresource内Java fixtureは **1** 件。両者の合計は **2726** 件です。

- [src/test/resources/review-evaluation/Example.java](../../src/test/resources/review-evaluation/Example.java#L1)

## 主要パッケージ（トップレベル集約）

| 領域 | ファイル数 |
| --- | --- |
| core | 261 |
| activity | 114 |
| event | 101 |
| web | 85 |
| topic | 71 |
| application | 54 |
| voice | 50 |
| memory | 47 |
| subagent | 45 |
| ui | 42 |
| paper | 41 |
| computeruse | 39 |
| externalagent | 37 |
| llm | 35 |
| feed | 34 |
| websearch | 33 |
| chatgpt | 29 |
| skills | 27 |
| storage | 23 |
| conversation | 21 |
| slack | 21 |
| goal | 19 |
| workcontext | 19 |
| image | 18 |
| summarize | 18 |
| http | 17 |
| temporal | 17 |
| bluesky | 16 |
| checkpoint | 16 |
| dot | 15 |
| episode | 14 |
| interest | 14 |
| vectordocument | 13 |
| attention | 12 |
| briefing | 11 |
| mcp | 10 |
| timing | 10 |
| vectorstore | 10 |
| github | 9 |
| (root) | 8 |
| doctor | 8 |
| reflection | 8 |
| reminder | 8 |
| task | 8 |
| artifact | 7 |
| googlecalendar | 7 |
| launcher | 6 |
| urlfetch | 6 |
| evaluation | 5 |
| planning | 4 |
| search | 4 |
| config | 3 |
| google | 3 |
| review | 3 |
| sound | 2 |
| text | 2 |

## 大きなJavaファイル上位20（本番、コメント・空行込み）

| ファイル | 行数 | AST型宣言数 |
| --- | --- | --- |
| [src/main/java/dev/mikoto2000/rei/core/Tools.java](../../src/main/java/dev/mikoto2000/rei/core/Tools.java#L1) | 3750 | 22 |
| [src/main/java/dev/mikoto2000/rei/core/chat/ChatExecutionService.java](../../src/main/java/dev/mikoto2000/rei/core/chat/ChatExecutionService.java#L1) | 1636 | 4 |
| [src/main/java/dev/mikoto2000/rei/subagent/ChatGptSubAgentBackend.java](../../src/main/java/dev/mikoto2000/rei/subagent/ChatGptSubAgentBackend.java#L1) | 1437 | 9 |
| [src/main/java/dev/mikoto2000/rei/ui/shell/ShellAgentEventRenderer.java](../../src/main/java/dev/mikoto2000/rei/ui/shell/ShellAgentEventRenderer.java#L1) | 1392 | 5 |
| [src/main/java/dev/mikoto2000/rei/externalagent/ExternalAgentDelegationService.java](../../src/main/java/dev/mikoto2000/rei/externalagent/ExternalAgentDelegationService.java#L1) | 1319 | 7 |
| [src/main/java/dev/mikoto2000/rei/goal/GoalRepository.java](../../src/main/java/dev/mikoto2000/rei/goal/GoalRepository.java#L1) | 1149 | 8 |
| [src/main/java/dev/mikoto2000/rei/checkpoint/PersistentCheckpointService.java](../../src/main/java/dev/mikoto2000/rei/checkpoint/PersistentCheckpointService.java#L1) | 1142 | 2 |
| [src/main/java/dev/mikoto2000/rei/activity/ActivityEvidencePipeline.java](../../src/main/java/dev/mikoto2000/rei/activity/ActivityEvidencePipeline.java#L1) | 1097 | 4 |
| [src/main/java/dev/mikoto2000/rei/event/AgentEventFactory.java](../../src/main/java/dev/mikoto2000/rei/event/AgentEventFactory.java#L1) | 1055 | 1 |
| [src/main/java/dev/mikoto2000/rei/subagent/SubAgentRunner.java](../../src/main/java/dev/mikoto2000/rei/subagent/SubAgentRunner.java#L1) | 1001 | 5 |
| [src/main/java/dev/mikoto2000/rei/checkpoint/PersistentCheckpointRepository.java](../../src/main/java/dev/mikoto2000/rei/checkpoint/PersistentCheckpointRepository.java#L1) | 1000 | 9 |
| [src/main/java/dev/mikoto2000/rei/core/RepositoryMapService.java](../../src/main/java/dev/mikoto2000/rei/core/RepositoryMapService.java#L1) | 947 | 14 |
| [src/main/java/dev/mikoto2000/rei/dot/DotRequestRepository.java](../../src/main/java/dev/mikoto2000/rei/dot/DotRequestRepository.java#L1) | 933 | 2 |
| [src/main/java/dev/mikoto2000/rei/core/stagnation/RunExecutionContext.java](../../src/main/java/dev/mikoto2000/rei/core/stagnation/RunExecutionContext.java#L1) | 902 | 4 |
| [src/main/java/dev/mikoto2000/rei/vectorstore/SqliteVectorStore.java](../../src/main/java/dev/mikoto2000/rei/vectorstore/SqliteVectorStore.java#L1) | 901 | 5 |
| [src/main/java/dev/mikoto2000/rei/ReiApplication.java](../../src/main/java/dev/mikoto2000/rei/ReiApplication.java#L1) | 876 | 2 |
| [src/main/java/dev/mikoto2000/rei/core/command/StorageCommand.java](../../src/main/java/dev/mikoto2000/rei/core/command/StorageCommand.java#L1) | 869 | 25 |
| [src/main/java/dev/mikoto2000/rei/activity/ActivityCommand.java](../../src/main/java/dev/mikoto2000/rei/activity/ActivityCommand.java#L1) | 847 | 14 |
| [src/main/java/dev/mikoto2000/rei/core/SemanticPatchReviewService.java](../../src/main/java/dev/mikoto2000/rei/core/SemanticPatchReviewService.java#L1) | 833 | 13 |
| [src/main/java/dev/mikoto2000/rei/temporal/PersistentAgentScheduler.java](../../src/main/java/dev/mikoto2000/rei/temporal/PersistentAgentScheduler.java#L1) | 832 | 7 |

## 大きなJavaクラス上位20（javac ASTの宣言開始〜終端、内部のコメント・空行込み）

ファイル全体の行数とは区別します。ネスト型はそれぞれ計測し、外側の型のspanにも含まれます。全本番型は [class-sizes.tsv](data/class-sizes.tsv)。

| クラス | ソース | クラスの行数 |
| --- | --- | --- |
| core.Tools | [src/main/java/dev/mikoto2000/rei/core/Tools.java](../../src/main/java/dev/mikoto2000/rei/core/Tools.java#L54) | 3697 |
| core.chat.ChatExecutionService | [src/main/java/dev/mikoto2000/rei/core/chat/ChatExecutionService.java](../../src/main/java/dev/mikoto2000/rei/core/chat/ChatExecutionService.java#L63) | 1574 |
| subagent.ChatGptSubAgentBackend | [src/main/java/dev/mikoto2000/rei/subagent/ChatGptSubAgentBackend.java](../../src/main/java/dev/mikoto2000/rei/subagent/ChatGptSubAgentBackend.java#L66) | 1372 |
| ui.shell.ShellAgentEventRenderer | [src/main/java/dev/mikoto2000/rei/ui/shell/ShellAgentEventRenderer.java](../../src/main/java/dev/mikoto2000/rei/ui/shell/ShellAgentEventRenderer.java#L66) | 1327 |
| externalagent.ExternalAgentDelegationService | [src/main/java/dev/mikoto2000/rei/externalagent/ExternalAgentDelegationService.java](../../src/main/java/dev/mikoto2000/rei/externalagent/ExternalAgentDelegationService.java#L44) | 1276 |
| goal.GoalRepository | [src/main/java/dev/mikoto2000/rei/goal/GoalRepository.java](../../src/main/java/dev/mikoto2000/rei/goal/GoalRepository.java#L27) | 1123 |
| checkpoint.PersistentCheckpointService | [src/main/java/dev/mikoto2000/rei/checkpoint/PersistentCheckpointService.java](../../src/main/java/dev/mikoto2000/rei/checkpoint/PersistentCheckpointService.java#L51) | 1092 |
| activity.ActivityEvidencePipeline | [src/main/java/dev/mikoto2000/rei/activity/ActivityEvidencePipeline.java](../../src/main/java/dev/mikoto2000/rei/activity/ActivityEvidencePipeline.java#L23) | 1075 |
| event.AgentEventFactory | [src/main/java/dev/mikoto2000/rei/event/AgentEventFactory.java](../../src/main/java/dev/mikoto2000/rei/event/AgentEventFactory.java#L21) | 1035 |
| checkpoint.PersistentCheckpointRepository | [src/main/java/dev/mikoto2000/rei/checkpoint/PersistentCheckpointRepository.java](../../src/main/java/dev/mikoto2000/rei/checkpoint/PersistentCheckpointRepository.java#L27) | 974 |
| subagent.SubAgentRunner | [src/main/java/dev/mikoto2000/rei/subagent/SubAgentRunner.java](../../src/main/java/dev/mikoto2000/rei/subagent/SubAgentRunner.java#L56) | 946 |
| dot.DotRequestRepository | [src/main/java/dev/mikoto2000/rei/dot/DotRequestRepository.java](../../src/main/java/dev/mikoto2000/rei/dot/DotRequestRepository.java#L29) | 905 |
| core.RepositoryMapService | [src/main/java/dev/mikoto2000/rei/core/RepositoryMapService.java](../../src/main/java/dev/mikoto2000/rei/core/RepositoryMapService.java#L54) | 894 |
| vectorstore.SqliteVectorStore | [src/main/java/dev/mikoto2000/rei/vectorstore/SqliteVectorStore.java](../../src/main/java/dev/mikoto2000/rei/vectorstore/SqliteVectorStore.java#L24) | 878 |
| core.stagnation.RunExecutionContext | [src/main/java/dev/mikoto2000/rei/core/stagnation/RunExecutionContext.java](../../src/main/java/dev/mikoto2000/rei/core/stagnation/RunExecutionContext.java#L34) | 869 |
| core.command.StorageCommand | [src/main/java/dev/mikoto2000/rei/core/command/StorageCommand.java](../../src/main/java/dev/mikoto2000/rei/core/command/StorageCommand.java#L14) | 856 |
| activity.ActivityCommand | [src/main/java/dev/mikoto2000/rei/activity/ActivityCommand.java](../../src/main/java/dev/mikoto2000/rei/activity/ActivityCommand.java#L11) | 837 |
| ReiApplication | [src/main/java/dev/mikoto2000/rei/ReiApplication.java](../../src/main/java/dev/mikoto2000/rei/ReiApplication.java#L48) | 829 |
| voice.VoiceInputCoordinator | [src/main/java/dev/mikoto2000/rei/voice/VoiceInputCoordinator.java](../../src/main/java/dev/mikoto2000/rei/voice/VoiceInputCoordinator.java#L24) | 809 |
| temporal.PersistentAgentScheduler | [src/main/java/dev/mikoto2000/rei/temporal/PersistentAgentScheduler.java](../../src/main/java/dev/mikoto2000/rei/temporal/PersistentAgentScheduler.java#L31) | 802 |

## 全本番パッケージ

| パッケージ | ファイル数 | bytecodeクラス | fan-in / fan-out（異なるpackage数） |
| --- | --- | --- | --- |
| dev.mikoto2000.rei | 8 | 11 | 1 / 30 |
| activity | 99 | 234 | 6 / 12 |
| activity.behavior | 15 | 28 | 1 / 6 |
| application.cli | 4 | 19 | 1 / 0 |
| application.input | 5 | 7 | 3 / 3 |
| application.project | 2 | 3 | 1 / 1 |
| application.read | 4 | 4 | 3 / 6 |
| application.run | 17 | 22 | 23 / 32 |
| application.session | 13 | 14 | 28 / 9 |
| application.state | 4 | 4 | 10 / 7 |
| application.task | 5 | 7 | 2 / 12 |
| artifact | 7 | 10 | 7 / 8 |
| attention | 12 | 21 | 4 / 6 |
| bluesky | 14 | 29 | 5 / 4 |
| bluesky.command | 2 | 6 | 3 / 1 |
| briefing | 9 | 11 | 6 / 6 |
| briefing.command | 2 | 3 | 2 / 4 |
| chatgpt | 29 | 55 | 7 / 10 |
| checkpoint | 16 | 44 | 14 / 16 |
| computeruse | 39 | 58 | 5 / 5 |
| config | 3 | 3 | 3 / 1 |
| conversation | 21 | 34 | 23 / 11 |
| core | 34 | 172 | 8 / 21 |
| core.actionplan | 5 | 5 | 4 / 1 |
| core.chat | 19 | 41 | 54 / 31 |
| core.checkpoint | 5 | 5 | 2 / 1 |
| core.command | 17 | 70 | 8 / 14 |
| core.completion | 17 | 25 | 14 / 1 |
| core.configuration | 12 | 13 | 5 / 45 |
| core.contextbudget | 30 | 49 | 15 / 11 |
| core.datasource | 4 | 4 | 22 / 0 |
| core.dependency | 15 | 28 | 13 / 15 |
| core.diagnostic | 2 | 3 | 5 / 3 |
| core.execution | 6 | 6 | 11 / 7 |
| core.filesummary | 5 | 5 | 2 / 1 |
| core.policy | 15 | 24 | 20 / 17 |
| core.predicate | 2 | 4 | 2 / 0 |
| core.process | 10 | 12 | 3 / 4 |
| core.project | 10 | 16 | 45 / 6 |
| core.recentchanges | 5 | 5 | 2 / 1 |
| core.relatedgraph | 5 | 5 | 2 / 2 |
| core.searchcache | 5 | 6 | 2 / 0 |
| core.service | 8 | 9 | 29 / 3 |
| core.sqlitevec | 8 | 14 | 2 / 1 |
| core.stagnation | 11 | 23 | 26 / 15 |
| core.taskstate | 6 | 6 | 6 / 2 |
| core.working | 5 | 6 | 10 / 4 |
| doctor | 8 | 16 | 3 / 11 |
| dot | 15 | 18 | 11 / 14 |
| episode | 14 | 19 | 3 / 15 |
| evaluation | 5 | 20 | 0 / 4 |
| event | 101 | 114 | 61 / 13 |
| externalagent | 37 | 81 | 13 / 16 |
| feed | 32 | 37 | 10 / 4 |
| feed.command | 2 | 13 | 3 / 4 |
| github | 9 | 18 | 5 / 8 |
| goal | 19 | 57 | 12 / 20 |
| google | 3 | 3 | 2 / 3 |
| googlecalendar | 5 | 7 | 9 / 1 |
| googlecalendar.command | 2 | 7 | 3 / 1 |
| http | 13 | 19 | 6 / 1 |
| http.cache | 4 | 11 | 2 / 3 |
| image | 15 | 16 | 5 / 7 |
| image.command | 3 | 3 | 1 / 6 |
| interest | 12 | 14 | 6 / 4 |
| interest.command | 2 | 5 | 3 / 1 |
| launcher | 6 | 9 | 2 / 0 |
| llm | 28 | 38 | 36 / 41 |
| llm.capture | 7 | 18 | 7 / 4 |
| mcp | 10 | 13 | 3 / 14 |
| memory.command | 4 | 19 | 4 / 11 |
| memory.configuration | 5 | 8 | 4 / 1 |
| memory.model | 8 | 8 | 6 / 0 |
| memory.service | 28 | 47 | 9 / 13 |
| memory.util | 2 | 2 | 4 / 0 |
| paper | 41 | 58 | 6 / 9 |
| planning | 4 | 9 | 1 / 13 |
| reflection | 8 | 17 | 4 / 8 |
| reminder | 6 | 7 | 5 / 1 |
| reminder.command | 2 | 6 | 2 / 1 |
| review | 3 | 13 | 8 / 5 |
| search | 4 | 4 | 7 / 3 |
| skills | 25 | 32 | 9 / 7 |
| skills.command | 2 | 6 | 2 / 3 |
| slack | 21 | 37 | 3 / 11 |
| sound | 2 | 2 | 2 / 2 |
| storage | 23 | 63 | 10 / 7 |
| subagent | 45 | 102 | 11 / 18 |
| summarize | 16 | 17 | 6 / 7 |
| summarize.command | 2 | 2 | 2 / 5 |
| task | 6 | 6 | 7 / 3 |
| task.command | 2 | 8 | 3 / 1 |
| temporal | 17 | 27 | 14 / 12 |
| text | 2 | 2 | 2 / 0 |
| timing | 10 | 30 | 6 / 4 |
| topic | 71 | 77 | 9 / 10 |
| ui.projection | 11 | 12 | 0 / 1 |
| ui.shell | 26 | 40 | 4 / 46 |
| ui.shell.sound | 5 | 5 | 9 / 2 |
| urlfetch | 6 | 6 | 5 / 3 |
| vectordocument | 13 | 15 | 9 / 4 |
| vectorstore | 10 | 22 | 5 / 4 |
| voice | 50 | 92 | 6 / 11 |
| web | 85 | 231 | 5 / 57 |
| websearch | 33 | 51 | 13 / 6 |
| workcontext | 19 | 29 | 13 / 11 |

## 参照先の集中（fan-in） 上位10

| パッケージ | 異なるpackage数 |
| --- | --- |
| event | 61 |
| core.chat | 54 |
| core.project | 45 |
| llm | 36 |
| core.service | 29 |
| application.session | 28 |
| core.stagnation | 26 |
| application.run | 23 |
| conversation | 23 |
| core.datasource | 22 |

## 依存先の広さ（fan-out） 上位10

| パッケージ | 異なるpackage数 |
| --- | --- |
| web | 57 |
| ui.shell | 46 |
| core.configuration | 45 |
| llm | 41 |
| application.run | 32 |
| core.chat | 31 |
| dev.mikoto2000.rei | 30 |
| core | 21 |
| goal | 20 |
| subagent | 18 |

## 参照されるクラス上位20（異なる参照元パッケージ数）

| クラス | 参照元パッケージ数 |
| --- | --- |
| core.chat.AgentRunContext | 41 |
| core.project.ProjectContext | 41 |
| event.AgentEventFactory | 33 |
| core.project.ProjectService | 32 |
| event.AgentEvent | 32 |
| event.AgentEventPublisher | 32 |
| core.chat.AgentRunScope | 31 |
| event.CredentialRedactor | 29 |
| event.AgentEventType | 24 |
| event.FailureMessages | 24 |
| core.chat.RunCancellation | 21 |
| llm.LlmModelProvider | 21 |
| core.chat.AgentRunContext$Mode | 20 |
| core.chat.AgentRunContext$RequestSource | 20 |
| core.datasource.ReiDataDirectory | 19 |
| core.service.CommandCancellationService | 18 |
| event.ErrorInformation | 18 |
| core.service.ModelHolderService | 17 |
| core.chat.ToolLoopSupport | 16 |
| core.stagnation.RunExecutionContext | 16 |

## Configuration配置（注釈・メタ注釈・包含クラスにより判定）

| クラス | 外向き内部クラス依存数 |
| --- | --- |
| ReiApplication | 80 |
| ReiApplication$1 | 1 |
| ReiApplication$ConfirmationReader | 0 |
| activity.ActivityConfiguration | 60 |
| activity.ActivityConfiguration$1 | 1 |
| activity.ActivityConfiguration$ActivityJob | 1 |
| activity.ActivityConfiguration$ClassificationReloadJob | 1 |
| activity.ActivityConfiguration$PeriodCoachingJob | 1 |
| activity.ActivityConfiguration$TemporalActivityJob | 1 |
| activity.behavior.BehaviorConfiguration | 15 |
| activity.behavior.BehaviorConfiguration$BehaviorJob | 1 |
| application.task.TaskManagerConfiguration | 14 |
| artifact.ArtifactConfiguration | 8 |
| chatgpt.PlanConfiguration | 25 |
| computeruse.ComputerUseConfiguration | 28 |
| config.HttpClientConfiguration | 0 |
| conversation.SessionHistoryConfiguration | 11 |
| core.actionplan.ActionPlanConfiguration | 1 |
| core.chat.AgentRunConfiguration | 19 |
| core.checkpoint.CheckpointConfiguration | 3 |
| core.configuration.AiConfiguration | 69 |
| core.configuration.AsyncConfiguration | 0 |
| core.configuration.DataSourceConfiguration | 4 |
| core.configuration.NativeHintsConfiguration | 0 |
| core.configuration.PicocliHintsConfiguration | 1 |
| core.configuration.VectorStoreConfiguration | 3 |
| core.contextbudget.ContextBudgetConfiguration | 1 |
| core.contextbudget.ContextCompressionConfiguration | 22 |
| core.filesummary.FileSummaryCacheConfiguration | 1 |
| core.project.ProjectScopeConfiguration | 2 |
| core.recentchanges.RecentChangesConfiguration | 1 |
| core.relatedgraph.RelatedFileGraphConfiguration | 1 |
| core.searchcache.SearchResultCacheConfiguration | 1 |
| core.stagnation.StagnationConfiguration | 1 |
| core.taskstate.TaskStateConfiguration | 1 |
| core.working.WorkingSetConfiguration | 10 |
| dot.DotConfiguration | 3 |
| episode.EpisodeDenseConfiguration | 11 |
| event.EventStorageConfiguration | 5 |
| event.ToolEventConfiguration | 3 |
| externalagent.ExternalAgentConfiguration | 12 |
| github.GitHubWebhookConfiguration | 11 |
| llm.EmbeddingBudgetConfiguration | 1 |
| llm.EmbeddingBudgetConfiguration$1 | 1 |
| llm.OpenAiSdkConfiguration | 3 |
| llm.OpenAiSdkConfiguration$1 | 1 |
| mcp.McpConfiguration | 20 |
| memory.configuration.MemoryDataSourceConfiguration | 2 |
| planning.DailyPlanningConfiguration | 13 |
| skills.SemanticSkillConfiguration | 6 |
| slack.SlackAgentConfiguration | 21 |
| slack.SlackConfiguration | 11 |
| slack.SlackConfiguration$HeadlessRuns | 7 |
| storage.StorageMigrationConfiguration | 8 |
| storage.StorageMigrationConfiguration$StartupGate | 4 |
| storage.StorageMigrationConfiguration$StartupGate$Shared | 1 |
| subagent.DurableSubAgentConfiguration | 7 |
| subagent.SubAgentConfiguration | 11 |
| temporal.TemporalConfiguration | 1 |
| topic.CuriosityQueueConfiguration | 3 |
| topic.TopicGeneratorConfiguration | 30 |
| ui.shell.ShellUiConfiguration | 4 |
| voice.VoiceConfiguration | 34 |
| web.BackendInstanceConfiguration | 2 |
| web.BackgroundRunConfiguration | 11 |
| web.SecurityConfig | 2 |
| web.WebApiConfiguration | 19 |
| web.WebApiConfiguration$Maintenance | 2 |
| workcontext.WorkContextConfiguration | 1 |

全package辺は [package-edges.tsv](data/package-edges.tsv)。class辺・行番号付き証拠は `target/architecture/dependencies.tsv` に再生成します。wiringはConfigurationを依存元とする辺、businessはそれ以外。`@ConfigurationProperties`はConfigurationとは区別します。Bean factoryメソッドを通常の@Component内に置く場合や、Configuration内の業務処理はこの二分類で完全には判別できず、手動確認が必要です。
