package dev.mikoto2000.rei.llm;

import java.util.Map;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** Native request generation rebuilt from a credential-free accepted snapshot. */
final class RunNativeOptions {
  private RunNativeOptions() {}

  static Map<String, Object> generation(ToolCallingChatOptions options) {
    var values = new java.util.LinkedHashMap<String, Object>();
    put(values, "temperature", options.getTemperature());
    put(values, "top_p", options.getTopP());
    if (options instanceof OpenAiChatOptions chat) {
      put(values, "max_tokens", chat.getMaxTokens());
      put(values, "max_completion_tokens", chat.getMaxCompletionTokens());
      put(values, "reasoning_effort", chat.getReasoningEffort());
      put(values, "service_tier", chat.getServiceTier());
      put(values, "frequency_penalty", chat.getFrequencyPenalty());
      put(values, "presence_penalty", chat.getPresencePenalty());
      put(values, "stop", chat.getStopSequences());
      put(values, "seed", chat.getSeed());
      put(values, "n", chat.getN());
      put(values, "logit_bias", chat.getLogitBias());
      put(values, "logprobs", chat.getLogprobs());
      put(values, "top_logprobs", chat.getTopLogprobs());
      put(values, "verbosity", chat.getVerbosity());
      put(values, "strict", chat.getStrict());
      put(values, "parallel_tool_calls", chat.getParallelToolCalls());
      put(values, "store", chat.getStore());
    } else if (options instanceof OpenAiResponsesChatOptions responses) {
      put(values, "max_output_tokens", responses.getMaxOutputTokens());
      put(values, "reasoning_effort", responses.getReasoningEffort());
      put(values, "service_tier", responses.getServiceTier());
      put(values, "reasoning_summary", responses.getReasoningSummary());
      put(values, "verbosity", responses.getVerbosity());
      put(values, "strict", responses.getStrict());
      put(values, "parallel_tool_calls", responses.getParallelToolCalls());
      put(values, "max_tool_calls", responses.getMaxToolCalls());
      put(values, "include", responses.getInclude());
      put(values, "truncation", responses.getTruncation());
    } else throw new IllegalArgumentException("Unknown native Run API");
    return RunGenerationSettings.copy(values);
  }

  private static void put(Map<String, Object> values, String key, Object value) {
    if (value != null) values.put(key, value);
  }

  static ToolCallingChatOptions options(RunLlmConnection connection) {
    var context = new java.util.HashMap<String, Object>();
    context.put(dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY, connection);
    context.put(ResolvedLlmEndpoint.CONTEXT_KEY, connection.endpoint());
    if (connection.endpoint().capabilities().maxTools() != null)
      context.put(LlmCapabilities.MAX_TOOLS_CONTEXT_KEY, connection.endpoint().capabilities().maxTools());
    if (connection.endpoint().apiType() == LlmApiType.CHAT_COMPLETIONS) {
      var builder = OpenAiChatOptions.builder().model(connection.endpoint().model())
          .maxRetries(0).timeout(connection.endpoint().readTimeout()).toolContext(context);
      connection.generation().forEach((name, value) -> {
        switch (name) {
          case "temperature" -> builder.temperature(number(name, value));
          case "top_p" -> builder.topP(number(name, value));
          case "max_tokens" -> builder.maxTokens(integer(name, value));
          case "max_completion_tokens" -> builder.maxCompletionTokens(integer(name, value));
          case "reasoning_effort" -> builder.reasoningEffort(string(name, value));
          case "service_tier" -> builder.serviceTier(string(name, value));
          case "frequency_penalty" -> builder.frequencyPenalty(number(name, value));
          case "presence_penalty" -> builder.presencePenalty(number(name, value));
          case "stop" -> builder.stop(strings(name, value));
          case "seed" -> builder.seed(signedInteger(name, value));
          case "n" -> builder.n(integer(name, value));
          case "logit_bias" -> builder.logitBias(integerMap(name, value));
          case "logprobs" -> builder.logprobs(bool(name, value));
          case "top_logprobs" -> builder.topLogprobs(signedInteger(name, value));
          case "verbosity" -> builder.verbosity(string(name, value));
          case "strict" -> builder.strict(bool(name, value));
          case "parallel_tool_calls" -> builder.parallelToolCalls(bool(name, value));
          case "store" -> builder.store(bool(name, value));
          default -> throw unsupported(name);
        }
      });
      return builder.build();
    }
    var builder = OpenAiResponsesChatOptions.builder().model(connection.endpoint().model())
        .maxRetries(0).timeout(connection.endpoint().readTimeout()).toolContext(context);
    connection.generation().forEach((name, value) -> {
      switch (name) {
        case "temperature" -> builder.temperature(number(name, value));
        case "top_p" -> builder.topP(number(name, value));
        case "max_output_tokens" -> builder.maxOutputTokens(integer(name, value));
        case "reasoning_effort" -> builder.reasoningEffort(string(name, value));
        case "service_tier" -> builder.serviceTier(string(name, value));
        case "reasoning_summary" -> builder.reasoningSummary(string(name, value));
        case "verbosity" -> builder.verbosity(string(name, value));
        case "strict" -> builder.strict(bool(name, value));
        case "parallel_tool_calls" -> builder.parallelToolCalls(bool(name, value));
        case "max_tool_calls" -> builder.maxToolCalls(integer(name, value));
        case "include" -> builder.include(strings(name, value));
        case "truncation" -> builder.truncation(string(name, value));
        default -> throw unsupported(name);
      }
    });
    return builder.build();
  }

  private static Double number(String name, Object value) {
    if (value instanceof Number number) return number.doubleValue();
    throw unsupported(name);
  }

  private static Integer integer(String name, Object value) {
    if (value instanceof Integer number && number > 0) return number;
    throw unsupported(name);
  }

  private static String string(String name, Object value) {
    if (value instanceof String text) return text;
    throw unsupported(name);
  }

  private static Integer signedInteger(String name, Object value) {
    if (value instanceof Integer number) return number;
    throw unsupported(name);
  }

  private static Boolean bool(String name, Object value) {
    if (value instanceof Boolean flag) return flag;
    throw unsupported(name);
  }

  private static java.util.List<String> strings(String name, Object value) {
    if (value instanceof java.util.List<?> list) {
      var copy = new java.util.ArrayList<String>();
      for (var item : list) copy.add(string(name, item));
      return java.util.List.copyOf(copy);
    }
    throw unsupported(name);
  }

  private static Map<String, Integer> integerMap(String name, Object value) {
    if (!(value instanceof Map<?, ?> map)) throw unsupported(name);
    var copy = new java.util.LinkedHashMap<String, Integer>();
    map.forEach((key, item) -> copy.put(string(name, key), signedInteger(name, item)));
    return Map.copyOf(copy);
  }

  private static IllegalArgumentException unsupported(String name) {
    return new IllegalArgumentException("Unsupported accepted Run setting: " + name);
  }
}
