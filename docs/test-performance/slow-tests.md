# Slow Test Rankings

改善前: 成功した2回目の全suite。class totalはsetupを含み、method時間とは集計範囲が異なる。

## Before: slowest classes TOP 50

| rank | test class | test count | total s | average method s | max method s |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | dev.mikoto2000.rei.core.ToolsTest | 111 | 26.167 | 0.236 | 2.029 |
| 2 | dev.mikoto2000.rei.memory.service.MemoryServicePropertyTest | 5 | 24.063 | 4.812 | 13.108 |
| 3 | dev.mikoto2000.rei.vectordocument.VectorDocumentServiceTest | 13 | 6.850 | 0.527 | 1.318 |
| 4 | dev.mikoto2000.rei.computeruse.ComputerUseApplicationTest | 2 | 6.826 | 0.846 | 1.409 |
| 5 | dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunnerTest | 8 | 4.380 | 0.547 | 2.215 |
| 6 | dev.mikoto2000.rei.memory.service.MemoryExporterPropertyTest | 1 | 4.232 | 4.231 | 4.231 |
| 7 | dev.mikoto2000.rei.vectorstore.SqliteVectorStoreTest | 15 | 2.762 | 0.184 | 0.233 |
| 8 | dev.mikoto2000.rei.paper.PaperWorkflowTest | 38 | 2.736 | 0.072 | 0.145 |
| 9 | dev.mikoto2000.rei.interest.InterestUpdateServicePropertyTest | 90 | 2.172 | 0.024 | 0.107 |
| 10 | dev.mikoto2000.rei.StartupProjectTest | 6 | 1.770 | 0.295 | 1.660 |
| 11 | dev.mikoto2000.rei.core.configuration.AiConfigurationTest | 4 | 1.759 | 0.440 | 1.428 |
| 12 | dev.mikoto2000.rei.core.process.BackgroundProcessManagerTest | 8 | 1.497 | 0.186 | 0.296 |
| 13 | dev.mikoto2000.rei.ReiApplicationTests | 3 | 1.394 | 0.003 | 0.003 |
| 14 | dev.mikoto2000.rei.memory.MemoryConsolidationTest | 10 | 1.360 | 0.136 | 0.655 |
| 15 | dev.mikoto2000.rei.ui.shell.sound.SoundNotificationServiceTest | 6 | 1.322 | 0.220 | 1.011 |
| 16 | dev.mikoto2000.rei.workcontext.WorkContextServiceTest | 8 | 1.306 | 0.163 | 0.311 |
| 17 | dev.mikoto2000.rei.memory.command.MemoryConsolidateCommandTest | 3 | 1.210 | 0.403 | 1.002 |
| 18 | dev.mikoto2000.rei.workcontext.LlmWorkContextExtractorTest | 3 | 1.015 | 0.338 | 1.007 |
| 19 | dev.mikoto2000.rei.computeruse.ShowUiComputerVisionModelTest | 8 | 1.007 | 0.125 | 0.325 |
| 20 | dev.mikoto2000.rei.conversation.ProjectHistoryRetrievalTest | 18 | 0.965 | 0.053 | 0.076 |
| 21 | dev.mikoto2000.rei.activity.behavior.BehaviorServiceTest | 17 | 0.933 | 0.054 | 0.714 |
| 22 | dev.mikoto2000.rei.feed.command.FeedCommandTest | 15 | 0.929 | 0.062 | 0.114 |
| 23 | dev.mikoto2000.rei.activity.ActivityCaptureTest | 30 | 0.894 | 0.029 | 0.731 |
| 24 | dev.mikoto2000.rei.subagent.SubAgentIntegrationTest | 1 | 0.754 | 0.097 | 0.097 |
| 25 | dev.mikoto2000.rei.activity.ActivityBackgroundStorageTest | 1 | 0.747 | 0.733 | 0.733 |
| 26 | dev.mikoto2000.rei.activity.OperationalSuggestionTest | 13 | 0.645 | 0.049 | 0.173 |
| 27 | dev.mikoto2000.rei.application.session.SessionQueryServiceTest | 11 | 0.625 | 0.056 | 0.561 |
| 28 | dev.mikoto2000.rei.memory.MemoryBoundaryTest | 5 | 0.615 | 0.122 | 0.279 |
| 29 | dev.mikoto2000.rei.subagent.SubAgentRunnerTest | 12 | 0.606 | 0.050 | 0.507 |
| 30 | dev.mikoto2000.rei.activity.ActivityIntegrationTest | 8 | 0.593 | 0.074 | 0.397 |
| 31 | dev.mikoto2000.rei.activity.ActivityTimelineTest | 10 | 0.589 | 0.058 | 0.063 |
| 32 | dev.mikoto2000.rei.interest.InterestDiscoveryJobPropertyTest | 25 | 0.584 | 0.023 | 0.080 |
| 33 | dev.mikoto2000.rei.activity.ActivityTrendQueryTest | 4 | 0.580 | 0.145 | 0.480 |
| 34 | dev.mikoto2000.rei.conversation.HistoryShellCommandTest | 8 | 0.560 | 0.070 | 0.119 |
| 35 | dev.mikoto2000.rei.memory.service.MemoryServiceTest | 7 | 0.548 | 0.078 | 0.117 |
| 36 | dev.mikoto2000.rei.computeruse.GroundingVerificationTest | 17 | 0.522 | 0.030 | 0.138 |
| 37 | dev.mikoto2000.rei.ui.shell.sound.SoundNotificationServicePropertyTest | 40 | 0.517 | 0.013 | 0.033 |
| 38 | dev.mikoto2000.rei.computeruse.VisionRefinementTest | 4 | 0.492 | 0.123 | 0.320 |
| 39 | dev.mikoto2000.rei.feed.FeedSummaryServiceTest | 10 | 0.489 | 0.048 | 0.097 |
| 40 | dev.mikoto2000.rei.paper.PaperFailureTest | 7 | 0.476 | 0.068 | 0.166 |
| 41 | dev.mikoto2000.rei.activity.ActivitySummaryQueryTest | 4 | 0.475 | 0.118 | 0.262 |
| 42 | dev.mikoto2000.rei.web.StatefulHttpTest | 1 | 0.460 | 0.460 | 0.460 |
| 43 | dev.mikoto2000.rei.computeruse.ComputerDiagnosticsTest | 4 | 0.454 | 0.113 | 0.253 |
| 44 | dev.mikoto2000.rei.bluesky.BlueskyReplyDeduplicationTest | 5 | 0.450 | 0.090 | 0.144 |
| 45 | dev.mikoto2000.rei.web.WebApiIntegrationTest | 2 | 0.439 | 0.219 | 0.282 |
| 46 | dev.mikoto2000.rei.topic.BehaviorConversationIntegrationTest | 11 | 0.430 | 0.039 | 0.089 |
| 47 | dev.mikoto2000.rei.core.chat.BackgroundCommandsTest | 5 | 0.418 | 0.083 | 0.202 |
| 48 | dev.mikoto2000.rei.memory.MemoryRetrievalTest | 4 | 0.406 | 0.101 | 0.176 |
| 49 | dev.mikoto2000.rei.application.session.SessionRepositoryTest | 5 | 0.395 | 0.079 | 0.356 |
| 50 | dev.mikoto2000.rei.feed.FeedUpdateServiceTest | 5 | 0.392 | 0.078 | 0.129 |

## Before: slowest methods TOP 20

| rank | class | method | duration s |
| ---: | --- | --- | ---: |
| 1 | dev.mikoto2000.rei.memory.service.MemoryServicePropertyTest | searchResultRespectsLimit | 13.108 |
| 2 | dev.mikoto2000.rei.memory.service.MemoryServicePropertyTest | saveFindRoundTrip | 6.826 |
| 3 | dev.mikoto2000.rei.memory.service.MemoryExporterPropertyTest | exportCountMatchesActiveMemoryCount | 4.231 |
| 4 | dev.mikoto2000.rei.memory.service.MemoryServicePropertyTest | deletedMemoryIsNotReturnedByListOrSearch | 3.462 |
| 5 | dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunnerTest | normalExitAndPeriodicActivity | 2.215 |
| 6 | dev.mikoto2000.rei.core.ToolsTest | runCommandForegroundMatchesLegacyTimeoutSemantics | 2.029 |
| 7 | dev.mikoto2000.rei.core.ToolsTest | runCommandForegroundMatchesLegacyCommandSemantics | 1.796 |
| 8 | dev.mikoto2000.rei.StartupProjectTest | mainRejectsInvalidInputBeforeSpringAndHelpExitsSuccessfully | 1.660 |
| 9 | dev.mikoto2000.rei.core.configuration.AiConfigurationTest | chatClientOmitsMcpToolCallbackProviderWhenUnavailable | 1.428 |
| 10 | dev.mikoto2000.rei.computeruse.ComputerUseApplicationTest | applicationWiresWorkflowWithoutTouchingDesktopOrNetwork | 1.409 |
| 11 | dev.mikoto2000.rei.vectordocument.VectorDocumentServiceTest | addListSearchAndDeleteDocuments | 1.318 |
| 12 | dev.mikoto2000.rei.core.ToolsTest | runCommandBackgroundMatchesLegacyRegistryStatusOutputAndKillSemantics | 1.092 |
| 13 | dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunnerTest | distinguishesTimeouts | 1.016 |
| 14 | dev.mikoto2000.rei.ui.shell.sound.SoundNotificationServiceTest | notifyFallsBackToConsoleOnTimeout | 1.011 |
| 15 | dev.mikoto2000.rei.workcontext.LlmWorkContextExtractorTest | timeoutInvalidOutputAndOversizedInputFailWithoutInvokingTools | 1.007 |
| 16 | dev.mikoto2000.rei.memory.command.MemoryConsolidateCommandTest | consolidateSkipsWhenConflictCheckTimesOut | 1.002 |
| 17 | dev.mikoto2000.rei.core.ToolsTest | executeShellCommandRunsWithDefaultShell | 0.995 |
| 18 | dev.mikoto2000.rei.core.ToolsTest | runCommandAutoPromotesSameLongProcessWithoutStartingTwice | 0.991 |
| 19 | dev.mikoto2000.rei.core.ToolsTest | readPdfFileExtractsBodyText | 0.952 |
| 20 | dev.mikoto2000.rei.core.ToolsTest | runCommandForegroundReturnsExistingShellResultShape | 0.928 |

## After: slowest classes TOP 20

改善後: fullの2回目。列はBeforeと同じ。

| rank | test class | test count | total s | average method s | max method s |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | dev.mikoto2000.rei.core.ToolsTest | 111 | 25.566 | 0.230 | 2.031 |
| 2 | dev.mikoto2000.rei.computeruse.ComputerUseApplicationTest | 2 | 6.550 | 0.853 | 1.398 |
| 3 | dev.mikoto2000.rei.vectordocument.VectorDocumentServiceTest | 13 | 4.606 | 0.354 | 0.861 |
| 4 | dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunnerTest | 8 | 4.482 | 0.560 | 2.226 |
| 5 | dev.mikoto2000.rei.paper.PaperWorkflowTest | 38 | 2.890 | 0.076 | 0.203 |
| 6 | dev.mikoto2000.rei.interest.InterestUpdateServicePropertyTest | 90 | 1.862 | 0.020 | 0.032 |
| 7 | dev.mikoto2000.rei.core.configuration.AiConfigurationTest | 4 | 1.832 | 0.458 | 1.520 |
| 8 | dev.mikoto2000.rei.core.process.BackgroundProcessManagerTest | 8 | 1.523 | 0.190 | 0.310 |
| 9 | dev.mikoto2000.rei.StartupProjectTest | 6 | 1.457 | 0.243 | 1.373 |
| 10 | dev.mikoto2000.rei.ui.shell.sound.SoundNotificationServiceTest | 6 | 1.320 | 0.220 | 1.012 |
| 11 | dev.mikoto2000.rei.memory.MemoryConsolidationTest | 10 | 1.313 | 0.131 | 0.658 |
| 12 | dev.mikoto2000.rei.workcontext.WorkContextServiceTest | 8 | 1.209 | 0.151 | 0.276 |
| 13 | dev.mikoto2000.rei.memory.command.MemoryConsolidateCommandTest | 3 | 1.203 | 0.401 | 1.001 |
| 14 | dev.mikoto2000.rei.ReiApplicationTests | 3 | 1.100 | 0.003 | 0.006 |
| 15 | dev.mikoto2000.rei.workcontext.LlmWorkContextExtractorTest | 3 | 1.020 | 0.339 | 1.007 |
| 16 | dev.mikoto2000.rei.activity.behavior.BehaviorServiceTest | 17 | 0.985 | 0.058 | 0.796 |
| 17 | dev.mikoto2000.rei.computeruse.ShowUiComputerVisionModelTest | 8 | 0.971 | 0.121 | 0.309 |
| 18 | dev.mikoto2000.rei.activity.ActivityCaptureTest | 30 | 0.912 | 0.030 | 0.742 |
| 19 | dev.mikoto2000.rei.conversation.ProjectHistoryRetrievalTest | 18 | 0.911 | 0.050 | 0.075 |
| 20 | dev.mikoto2000.rei.subagent.SubAgentIntegrationTest | 1 | 0.810 | 0.108 | 0.108 |

## Before TOP 20: comparison with After

| class | before s | after s | improvement % |
| --- | ---: | ---: | ---: |
| dev.mikoto2000.rei.core.ToolsTest | 26.167 | 25.566 | 2.3 |
| dev.mikoto2000.rei.memory.service.MemoryServicePropertyTest | 24.063 | 0.190 | 99.2 |
| dev.mikoto2000.rei.vectordocument.VectorDocumentServiceTest | 6.850 | 4.606 | 32.8 |
| dev.mikoto2000.rei.computeruse.ComputerUseApplicationTest | 6.826 | 6.550 | 4.0 |
| dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunnerTest | 4.380 | 4.482 | -2.3 |
| dev.mikoto2000.rei.memory.service.MemoryExporterPropertyTest | 4.232 | 0.226 | 94.7 |
| dev.mikoto2000.rei.vectorstore.SqliteVectorStoreTest | 2.762 | 0.381 | 86.2 |
| dev.mikoto2000.rei.paper.PaperWorkflowTest | 2.736 | 2.890 | -5.6 |
| dev.mikoto2000.rei.interest.InterestUpdateServicePropertyTest | 2.172 | 1.862 | 14.3 |
| dev.mikoto2000.rei.StartupProjectTest | 1.770 | 1.457 | 17.7 |
| dev.mikoto2000.rei.core.configuration.AiConfigurationTest | 1.759 | 1.832 | -4.2 |
| dev.mikoto2000.rei.core.process.BackgroundProcessManagerTest | 1.497 | 1.523 | -1.7 |
| dev.mikoto2000.rei.ReiApplicationTests | 1.394 | 1.100 | 21.1 |
| dev.mikoto2000.rei.memory.MemoryConsolidationTest | 1.360 | 1.313 | 3.5 |
| dev.mikoto2000.rei.ui.shell.sound.SoundNotificationServiceTest | 1.322 | 1.320 | 0.2 |
| dev.mikoto2000.rei.workcontext.WorkContextServiceTest | 1.306 | 1.209 | 7.4 |
| dev.mikoto2000.rei.memory.command.MemoryConsolidateCommandTest | 1.210 | 1.203 | 0.6 |
| dev.mikoto2000.rei.workcontext.LlmWorkContextExtractorTest | 1.015 | 1.020 | -0.5 |
| dev.mikoto2000.rei.computeruse.ShowUiComputerVisionModelTest | 1.007 | 0.971 | 3.6 |
| dev.mikoto2000.rei.conversation.ProjectHistoryRetrievalTest | 0.965 | 0.911 | 5.6 |
