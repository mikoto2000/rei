package dev.mikoto2000.rei.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.ModelHolderService;

@Component
public class OutputLimitReplanner {

  private static final Logger log = LoggerFactory.getLogger(OutputLimitReplanner.class);

  private final LlmModelProvider modelProvider;
  private final ModelHolderService modelHolderService;
  private final LlmProperties properties;
  private final OutputLimitReplanParser parser;

  public OutputLimitReplanner(LlmModelProvider modelProvider, ModelHolderService modelHolderService,
      LlmProperties properties) {
    this(modelProvider, modelHolderService, properties, new OutputLimitReplanParser());
  }

  OutputLimitReplanner(LlmModelProvider modelProvider, ModelHolderService modelHolderService,
      LlmProperties properties, OutputLimitReplanParser parser) {
    this.modelProvider = modelProvider;
    this.modelHolderService = modelHolderService;
    this.properties = properties;
    this.parser = parser;
  }

  public OutputLimitReplanPlan replan(OutputLimitReplanRequest request) {
    log.info("Output limit replan planner started: replanCount={}, remainingLlmCalls={}",
        request.replanCount(), request.remainingLlmCalls());
    Prompt prompt = new Prompt(buildPrompt(request),
        modelProvider.chatOptions(LlmFeature.OUTPUT_LIMIT_PLANNER, modelHolderService.get()));
    ChatResponse response = modelProvider.chatModel(LlmFeature.OUTPUT_LIMIT_PLANNER).call(prompt);
    String text = response.getResult().getOutput().getText();
    OutputLimitReplanPlan plan = parser.parse(text, properties.getOutputLimit().getMaxSubgoalsPerReplan());
    log.info("Output limit replan planner completed: subgoals={}", plan.subgoals().size());
    return plan;
  }

  private String buildPrompt(OutputLimitReplanRequest request) {
    return """
        You are a planner for an AI agent.
        Do not execute the task.
        Split the current goal into smaller semantic subgoals that can fit within one output limit.
        Return JSON only:
        {
          "subgoals": [{"id": "short-id", "goal": "specific subgoal"}],
          "finalGoal": "goal for integrating subgoal results"
        }

        Original user request:
        %s

        Current goal:
        %s

        Progress so far:
        %s

        Partial output generated before limit:
        %s

        Trigger:
        finish_reason == length

        Max output tokens per call:
        %d

        Remaining execution budget:
        replanCount=%d
        maxReplansPerGoal=%d
        remainingLlmCalls=%d
        maxSubgoalsPerReplan=%d
        """.formatted(
            safe(request.originalUserRequest()),
            safe(request.currentGoal()),
            safe(request.progressSoFar()),
            safe(request.partialOutput()),
            properties.getMaxOutputTokens(),
            request.replanCount(),
            request.maxReplansPerGoal(),
            request.remainingLlmCalls(),
            properties.getOutputLimit().getMaxSubgoalsPerReplan());
  }

  private String safe(String value) {
    return value == null || value.isBlank() ? "(none)" : value;
  }
}
