package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.contextbudget.ContextAssembler;
import dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties;
import dev.mikoto2000.rei.core.contextbudget.LlmConversationCompressor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Explicit test-only Spring composition: actual application/Core, owned callbacks and metering. */
public final class ResponsesOperationalLiveAcceptance implements AutoCloseable {
  static final class OwnedTools {
    volatile List<ToolCallback> callbacks = List.of();
    volatile AgentRunContext parent;
    volatile dev.mikoto2000.rei.llm.discovery.ToolDiscovery discovery;
    final java.util.concurrent.atomic.AtomicInteger childCalls =
        new java.util.concurrent.atomic.AtomicInteger();
  }

  final ResponsesAcceptanceRuntime runtime;
  final OwnedTools tools = new OwnedTools();
  final org.springframework.context.ConfigurableApplicationContext app;

  ResponsesOperationalLiveAcceptance(ResponsesAcceptanceRuntime runtime) throws Exception {
    if (!runtime.operational) throw new IllegalArgumentException("Operational snapshot required");
    this.runtime = runtime;
    Path data = runtime.root.resolve("operational-data");
    Files.createDirectories(data);
    // No classpath/default/user application.yaml imports. The owned config has no credentials.
    Path config = runtime.root.resolve("operational-isolated.yaml");
    Files.writeString(config, "{}\n");
    System.setProperty("rei.data-dir", data.toString());
    System.setProperty("spring.devtools.restart.enabled", "false");
    Path role = data.resolve("config/subagents/acceptance-reviewer.yaml");
    Files.createDirectories(role.getParent());
    String definition =
        "id: acceptance-reviewer\nname: Owned reviewer\ndescription: Owned tool-free synthetic reviewer\nsystemPrompt: Return the required validated SUCCESS JSON envelope for the supplied synthetic task. No tools.\ntools: []\nmaxSteps: 2\ntimeout: 30s\n";
    if (Files.exists(role)) {
      if (!Files.readString(role).equals(definition))
        throw new IllegalStateException("Owned role changed");
    } else Files.writeString(role, definition, java.nio.file.StandardOpenOption.CREATE_NEW);
    app =
        new org.springframework.boot.builder.SpringApplicationBuilder(
                dev.mikoto2000.rei.ReiApplication.class, OwnedConfiguration.class)
            .web(org.springframework.boot.WebApplicationType.NONE)
            .initializers(context -> {
              context.getBeanFactory().registerSingleton("acceptanceRuntime", runtime);
              context.getBeanFactory().registerSingleton("acceptanceOwnedTools", tools);
            })
            .run(
                "--spring.config.location=" + config.toUri(),
                "--spring.main.allow-bean-definition-overriding=true",
                "--spring.main.banner-mode=off",
                "--logging.level.root=OFF",
                "--rei.data-dir=" + data,
                "--rei.core.system-prompt=Owned synthetic acceptance only.",
                "--rei.core.chat-memory-max-messages=100",
                "--spring.ai.openai.base-url=https://disabled.invalid",
                "--spring.ai.openai.api-key=owned-disabled-key",
                "--spring.ai.openai.chat.options.model=" + runtime.model,
                "--spring.ai.model.chat=none",
                "--spring.ai.model.embedding=none",
                "--spring.ai.model.image=none",
                "--spring.ai.mcp.client.enabled=false",
                "--spring.ai.mcp.server.enabled=false",
                "--rei.embedding.enabled=false",
                "--rei.web.enabled=false",
                "--rei.slack.enabled=false",
                "--rei.secretary.enabled=false",
                "--rei.voice.enabled=false",
                "--rei.sound-notification.enabled=false",
                "--rei.attention.delivery.enabled=false",
                "--rei.dot-delegation.enabled=false",
                "--rei.subagents.durable-enabled=true",
                "--rei.subagents.standalone-max-llm-calls=2",
                "--rei.subagents.standalone-max-total-tokens=10000",
                "--rei.subagents.durable-max-total-tokens=10000",
                "--fullauto=false");
    if (app.getBean(LlmModelProvider.class) != runtime.models
        || !app.getBeansOfType(
                org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
                    .class)
            .isEmpty()
        || !dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
            .equals(data.toAbsolutePath())
        || !app.getBean(dev.mikoto2000.rei.core.policy.ToolPermissionProperties.class).enabled()
        || app.getBean(dev.mikoto2000.rei.computeruse.FullAutoOptions.class).enabled()) {
      app.close();
      throw new IllegalStateException("Application isolation preflight failed");
    }
    runtime.report(
        "APP_PREFLIGHT",
        Map.of(
            "success",
            true,
            "scheduler_processors",
            0,
            "web",
            false,
            "owned_data",
            true,
            "actual_core",
            true,
            "production_gate_unchanged",
            true,
            "budget",
            runtime.budget.snapshot()));
  }

  AgentRunContext owner(String suffix) {
    var clock = Clock.systemUTC();
    var projects = app.getBean(dev.mikoto2000.rei.core.project.ProjectService.class);
    var scope = projects.newClient().open();
    dev.mikoto2000.rei.core.project.ProjectContext project;
    try {
      projects.add(runtime.root.toString());
      projects.cd(runtime.root.toString());
      project = projects.currentContext();
    } finally {
      scope.close();
    }
    String identity = "operational-" + suffix + "-" + java.util.UUID.randomUUID();
    var owner = new AgentRunContext(identity, identity, runtime.root, project.id());
    app.getBean(dev.mikoto2000.rei.application.session.SessionRepository.class)
        .accept(
            new dev.mikoto2000.rei.application.session.SessionMetadata(
                owner.conversationId(),
                owner.projectId(),
                "Owned operational acceptance",
                clock.instant(),
                clock.instant()),
            () -> {});
    return owner;
  }

  dev.mikoto2000.rei.core.chat.ChatExecutionResult execute(AgentRunContext owner, String request) {
    return execute(owner, request, null);
  }

  dev.mikoto2000.rei.core.chat.ChatExecutionResult execute(
      AgentRunContext owner, String request, OutputLimitRunBudget.LlmCallReservation reservation) {
    app.getBean(dev.mikoto2000.rei.application.run.RunRegistry.class).register(owner);
    var result =
        new java.util.concurrent.atomic.AtomicReference<
            dev.mikoto2000.rei.core.chat.ChatExecutionResult>();
    app.getBean(dev.mikoto2000.rei.application.run.RunService.class)
        .execute(
            owner,
            () ->
                result.set(
                    app.getBean(dev.mikoto2000.rei.core.chat.ChatExecutionService.class)
                        .execute(
                            owner,
                            request,
                            new dev.mikoto2000.rei.core.chat.UserInterventionQueue(),
                            reservation)));
    if (result.get() == null) throw new IllegalStateException("Owned Run did not execute");
    return result.get();
  }

  void smoke() throws Exception {
    var result = execute(owner("smoke"), "Return exactly REI_OPERATIONAL_OK with no other text.");
    boolean correct = result.success() && "REI_OPERATIONAL_OK".equals(result.text().trim());
    runtime.report("APP_SMOKE", Map.of("success", correct, "budget", runtime.budget.snapshot()));
    if (!correct) throw new IllegalStateException("Actual application smoke criterion failed");
  }

  @Override
  public void close() {
    app.close();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class OwnedConfiguration {
    @Bean
    dev.mikoto2000.rei.subagent.SubAgentRunner subAgentRunner(
        ResponsesAcceptanceRuntime runtime,
        OwnedTools tools,
        dev.mikoto2000.rei.subagent.SubAgentRegistry registry,
        dev.mikoto2000.rei.subagent.SubAgentToolPolicy policy,
        dev.mikoto2000.rei.core.service.CommandCancellationService cancellation,
        dev.mikoto2000.rei.event.AgentEventFactory events,
        dev.mikoto2000.rei.event.AgentEventPublisher publisher,
        Clock clock) {
      var runner =
          new dev.mikoto2000.rei.subagent.SubAgentRunner(
              registry,
              policy,
              selected -> {
                if (selected != null && !runtime.model.equals(selected))
                  throw new IllegalArgumentException("Unapproved child model");
                ChatModel metered =
                    new ResponsesAcceptanceMeter(
                        runtime.models.subAgentChatModel(selected),
                        runtime.budget,
                        !runtime.target.equals("local-deepseek"),
                        true);
                return new ChatModel() {
                  public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                    return metered.getOptions();
                  }

                  public org.springframework.ai.chat.model.ChatResponse call(
                      org.springframework.ai.chat.prompt.Prompt prompt) {
                    throw new IllegalStateException(
                        "Owned child uses the existing streaming runner");
                  }

                  public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse>
                      stream(org.springframework.ai.chat.prompt.Prompt prompt) {
                    return reactor.core.publisher.Flux.defer(() -> {
                      var context =
                          ((org.springframework.ai.model.tool.ToolCallingChatOptions)
                                  prompt.getOptions())
                              .getToolContext();
                      if (!(context.get(AgentRunContext.class.getName())
                              instanceof AgentRunContext child)
                          || tools.parent == null
                          || child.runId().equals(tools.parent.runId())
                          || !child.conversationId().startsWith("subagent:")
                          || !runtime
                              .models
                              .resolvedEndpoint(LlmFeature.CHAT)
                              .equals(context.get(ResolvedLlmEndpoint.CONTEXT_KEY))
                          || context.containsKey("acceptance.parent-private-marker"))
                        throw new IllegalStateException(
                            "Child identity/endpoint/private context boundary failed");
                      tools.childCalls.incrementAndGet();
                      try {
                        runtime.report(
                            "APP_CHILD_BOUNDARY",
                            Map.of(
                                "success",
                                true,
                                "child_run",
                                child.runId(),
                                "parent_run",
                                tools.parent.runId()));
                      } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                      }
                      return metered.stream(prompt);
                    });
                  }
                };
              },
              selected -> runtime.models.requestOptions(LlmFeature.CHAT, runtime.model, false),
              List::of,
              cancellation,
              events,
              publisher,
              clock);
      runner.setRunOptionsFactory(runtime.models.subAgentOptionsFactory(() -> runtime.model));
      return runner;
    }

    @Bean
    OutputLimitReplanner outputLimitReplanner(
        ResponsesAcceptanceRuntime runtime,
        dev.mikoto2000.rei.core.service.ModelHolderService current) {
      return new OutputLimitReplanner(
          ResponsesOperationalScenarios.meteredModels(runtime, LlmFeature.OUTPUT_LIMIT_PLANNER),
          current,
          runtime.properties);
    }

    @Bean
    @org.springframework.context.annotation.DependsOn("storageMigrationLease")
    dev.mikoto2000.rei.application.run.RunRegistry acceptanceRunRegistry(
        Clock clock,
        @org.springframework.beans.factory.annotation.Qualifier("memoryConsolidationDataSource")
            javax.sql.DataSource source) {
      return new dev.mikoto2000.rei.application.run.RunRegistry(clock, source);
    }

    @Bean
    dev.mikoto2000.rei.application.run.RunService acceptanceRunService(
        dev.mikoto2000.rei.application.run.RunRegistry registry,
        dev.mikoto2000.rei.event.AgentEventBus bus,
        dev.mikoto2000.rei.event.AgentEventFactory events,
        dev.mikoto2000.rei.core.service.CommandCancellationService cancellation,
        dev.mikoto2000.rei.core.chat.ConversationInputRouter router) {
      return new dev.mikoto2000.rei.application.run.RunService(
          registry, bus, events, cancellation, router::cancelQueued);
    }

    @Bean
    static BeanFactoryPostProcessor removeBusinessScheduling() {
      return factory -> {
        var registry = (org.springframework.beans.factory.support.BeanDefinitionRegistry) factory;
        String name =
            org.springframework.scheduling.config.TaskManagementConfigUtils
                .SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
        if (registry.containsBeanDefinition(name)) registry.removeBeanDefinition(name);
      };
    }

    @Bean
    @Primary
    LlmProperties acceptanceProperties(ResponsesAcceptanceRuntime runtime) {
      return runtime.properties;
    }

    @Bean
    LlmModelProvider llmModelProvider(ResponsesAcceptanceRuntime runtime) {
      return runtime.models;
    }

    @Bean
    ChatModel chatModel() {
      return new ChatModel() {
        public org.springframework.ai.chat.model.ChatResponse call(
            org.springframework.ai.chat.prompt.Prompt prompt) {
          throw new IllegalStateException(
              "Default/Chat Completions network disabled in owned acceptance");
        }

        public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
          return org.springframework.ai.openai.OpenAiChatOptions.builder()
              .model("disabled")
              .build();
        }
      };
    }

    @Bean
    org.springframework.ai.embedding.EmbeddingModel embeddingModel() {
      return org.mockito.Mockito.mock(
          org.springframework.ai.embedding.EmbeddingModel.class,
          invocation -> {
            throw new IllegalStateException("Embedding disabled in owned acceptance");
          });
    }

    @Bean
    @Primary
    ContextAssembler acceptanceAssembler(
        ResponsesAcceptanceRuntime runtime,
        dev.mikoto2000.rei.core.contextbudget.TokenEstimator estimator,
        dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository summaries,
        dev.mikoto2000.rei.core.contextbudget.RawToolResultStore raw,
        dev.mikoto2000.rei.event.AgentEventFactory events,
        dev.mikoto2000.rei.event.AgentEventPublisher publisher,
        org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.review.ReviewTools>
            reviews,
        dev.mikoto2000.rei.tooldelivery.ReadingDelivery reading,
        org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.core.working.WorkingSet>
            working) {
      var summaryModels =
          new LlmModelProvider(runtime.metered(), new LlmProperties()) {
            @Override
            public ChatModel chatModel(String feature) {
              if (!LlmFeature.CHAT.equals(feature))
                throw new IllegalArgumentException("Unapproved summary feature");
              return runtime.metered();
            }

            @Override
            public org.springframework.ai.model.tool.ToolCallingChatOptions requestOptions(
                String feature, String selected, boolean streamUsage) {
              return runtime.models.requestOptions(feature, selected, streamUsage);
            }
          };
      var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
      beans.addBean("meteredSummaryModels", summaryModels);
      var settings = new ContextCompressionProperties();
      settings.setModelContextLimit(32000);
      settings.setCompletionReserve(2048);
      settings.setSafetyMargin(1000);
      settings.setThreshold(4500);
      settings.setHardLimit(8000);
      settings.setRecentTokens(1500);
      settings.setSummaryTokens(512);
      settings.setSummaryGenerationReserve(1024);
      settings.setSummaryInputTokens(12000);
      settings.setStandaloneSummaryMaxLlmCalls(10);
      settings.setStandaloneSummaryMaxTotalTokens(100000);
      settings.validate();
      var assembler =
          new ContextAssembler(
              settings,
              estimator,
              summaries,
              new dev.mikoto2000.rei.core.contextbudget.ToolResultCompressor(
                  raw, estimator, 8000, 2000),
              new LlmConversationCompressor(
                  beans.getBeanProvider(LlmModelProvider.class), settings),
              events,
              publisher);
      assembler.setReviewTools(reviews.getIfAvailable());
      assembler.setReadingDelivery(reading);
      assembler.setWorkingSet(() -> working.getObject().renderForPrompt());
      return assembler;
    }

    @Bean
    LlmChatClientProvider llmChatClientProvider(
        ResponsesAcceptanceRuntime runtime, OwnedTools tools, ContextAssembler assembler) {
      return new FixedLlmChatClientProvider(null) {
        private ChatClient ownedClient() {
          return ChatClient.builder(
                  new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(
                          runtime.metered(), assembler, 0)
                      .withToolDiscovery(tools.discovery))
              .defaultAdvisors(new RunAwareToolCallingAdvisor())
              .defaultSystem(
                  "Execute only the supplied owned synthetic acceptance task. Preserve facts and exact proofs; do not invent tool results.")
              .defaultTools(tools.callbacks.toArray())
              .build();
        }

        @Override
        public ChatClient chatClient(String feature) {
          if (!LlmFeature.CHAT.equals(feature))
            throw new IllegalArgumentException("Unapproved owned client feature");
          return ownedClient();
        }

        @Override
        public ChatClient parallelChatClient(AgentRunContext.Mode mode) {
          return ownedClient();
        }

        @Override
        public ChatClient runChatClient(
            AgentRunContext.Mode mode, LlmApiType api, ChatModel routedModel) {
          if (api != LlmApiType.RESPONSES)
            throw new IllegalArgumentException("Owned acceptance requires Responses");
          // This explicit fixed-client test composition owns its transport, callbacks and quota.
          return ownedClient();
        }
      };
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !args[2].startsWith("operational-"))
      throw new IllegalArgumentException("target ownedRoot operational-action required");
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.setLevel(ch.qos.logback.classic.Level.OFF);
    var runtime =
        new ResponsesAcceptanceRuntime(
            args[0], Path.of(args[1]), ResponsesAcceptanceRuntime.Candidate.OPERATIONAL);
    try (var test = new ResponsesOperationalLiveAcceptance(runtime)) {
      switch (args[2]) {
        case "operational-preflight" -> {}
        case "operational-smoke" -> test.smoke();
        case "operational-short" -> new ResponsesOperationalRounds(test, "short", 3).run("short");
        case "operational-medium" ->
            new ResponsesOperationalRounds(test, "medium", 10).run("medium");
        case "operational-medium-retest" ->
            new ResponsesOperationalRounds(test, "medium", 10, "after-summary-fix").run("medium");
        case "operational-long" -> new ResponsesOperationalRounds(test, "long", 25).run("long");
        case "operational-goal" -> ResponsesOperationalScenarios.goal(test);
        case "operational-planning" -> ResponsesOperationalScenarios.planning(test);
        case "operational-planning-retest" -> ResponsesOperationalScenarios.planning(test, true);
        case "operational-subagent" -> ResponsesOperationalScenarios.subagent(test);
        case "operational-subagent-retest" -> ResponsesOperationalScenarios.subagent(test, true);
        case "operational-cancel" -> ResponsesOperationalScenarios.cancel(test);
        case "operational-research" -> ResponsesOperationalResearch.run(test);
        case "operational-research-retest" ->
            ResponsesOperationalResearch.run(test, "operational-research-repeat");
        default -> throw new IllegalArgumentException("Unimplemented operational action");
      }
    } catch (Exception failure) {
      runtime.trace("OPERATIONAL_MAIN", failure);
      runtime.report(
          "OPERATIONAL_ACTION",
          Map.of("action", args[2], "success", false, "budget", runtime.budget.snapshot()));
      System.exit(1);
      return;
    }
    runtime.report(
        "OPERATIONAL_ACTION",
        Map.of("action", args[2], "success", true, "budget", runtime.budget.snapshot()));
    // App stores are closed and usage is settled; SDK executor keep-alive must not hold this owned
    // JVM.
    System.exit(0);
  }
}
