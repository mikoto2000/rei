package dev.mikoto2000.rei.llm;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** Independent server/model declarations intersected with Rei's adapter constraints. */
public record LlmEndpointCapabilities(
    Map<Capability, Support> route, Map<Capability, Support> server,
    Map<Capability, Support> model, Integer maxTools) {
  public enum Support {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN
  }

  public enum Capability {
    CHAT_COMPLETIONS,
    RESPONSES,
    FUNCTION_CALLING,
    TOOL_NAMESPACE,
    STREAMING,
    REASONING_REPLAY,
    REASONING_EFFORT,
    VISION,
    FILE_INPUT,
    STRUCTURED_OUTPUT,
    TEMPERATURE,
    TOP_P,
    MAX_OUTPUT_TOKENS,
    PREVIOUS_RESPONSE_ID,
    HOSTED_WEB_SEARCH,
    HOSTED_MCP,
    HOSTED_TOOL_SEARCH,
    REASONING_SUMMARY,
    TEXT_VERBOSITY,
    PARALLEL_TOOL_CALLS
  }

  public LlmEndpointCapabilities {
    route = freeze(route);
    server = freeze(server);
    model = freeze(model);
    if (maxTools != null && maxTools < 1)
      throw new IllegalArgumentException("max-tools must be positive");
  }

  private static Map<Capability, Support> freeze(Map<Capability, Support> source) {
    var copy = filled(Support.UNKNOWN);
    copy.putAll(source);
    copy.values().forEach(java.util.Objects::requireNonNull);
    return Collections.unmodifiableMap(copy);
  }

  @Override
  public Map<Capability, Support> route() {
    return Map.copyOf(route);
  }

  @Override
  public Map<Capability, Support> model() {
    return Map.copyOf(model);
  }

  @Override
  public Map<Capability, Support> server() {
    return Map.copyOf(server);
  }

  private static EnumMap<Capability, Support> filled(Support value) {
    var values = new EnumMap<Capability, Support>(Capability.class);
    for (var capability : Capability.values()) values.put(capability, value);
    return values;
  }

  @com.fasterxml.jackson.annotation.JsonProperty("effective")
  public Map<Capability, Support> effective() {
    var result = new EnumMap<Capability, Support>(Capability.class);
    for (var capability : Capability.values()) {
      var transport = route.get(capability);
      var implementation = server.get(capability);
      var declared = model.get(capability);
      result.put(
          capability,
          transport == Support.UNSUPPORTED || implementation == Support.UNSUPPORTED
              || declared == Support.UNSUPPORTED
              ? Support.UNSUPPORTED
              : transport == Support.UNKNOWN || implementation == Support.UNKNOWN
                  || declared == Support.UNKNOWN
                  ? Support.UNKNOWN
                  : Support.SUPPORTED);
    }
    return Collections.unmodifiableMap(result);
  }

  static LlmEndpointCapabilities resolve(
      LlmProperties.Server server,
      LlmProvider provider,
      LlmApiType api,
      Integer defaultMaxTools,
      String field, boolean named) {
    var transport = filled(Support.UNKNOWN);
    if (provider != LlmProvider.CHATGPT_PLAN) {
      transport = filled(Support.SUPPORTED);
      transport.put(
          Capability.CHAT_COMPLETIONS,
          api == LlmApiType.CHAT_COMPLETIONS ? Support.SUPPORTED : Support.UNSUPPORTED);
      transport.put(
          Capability.RESPONSES,
          api == LlmApiType.RESPONSES ? Support.SUPPORTED : Support.UNSUPPORTED);
      transport.put(Capability.TOOL_NAMESPACE, Support.UNSUPPORTED);
      transport.put(Capability.FILE_INPUT, Support.UNSUPPORTED);
      if (api == LlmApiType.CHAT_COMPLETIONS) {
        transport.put(Capability.REASONING_REPLAY, Support.UNSUPPORTED);
        transport.put(Capability.REASONING_EFFORT, Support.UNKNOWN);
        transport.put(Capability.REASONING_SUMMARY, Support.UNKNOWN);
        transport.put(Capability.TEXT_VERBOSITY, Support.UNKNOWN);
      } else {
        var replay = server == null ? null : server.getReasoningReplay();
        transport.put(
            Capability.REASONING_REPLAY,
            replay == null
                ? Support.UNKNOWN
                : replay == ReasoningReplayMode.NONE ? Support.UNSUPPORTED : Support.SUPPORTED);
      }
    } else {
      // The normal-chat Plan adapter is not implemented yet. Do not advertise its SDK
      // prototypes as an enabled connection. Hard preview restrictions still apply.
      transport.put(Capability.CHAT_COMPLETIONS, Support.UNSUPPORTED);
      for (var capability :
          EnumSet.of(Capability.TEMPERATURE, Capability.TOP_P, Capability.MAX_OUTPUT_TOKENS))
        transport.put(capability, Support.UNSUPPORTED);
    }
    for (var capability :
        EnumSet.of(
            Capability.PREVIOUS_RESPONSE_ID,
            Capability.HOSTED_WEB_SEARCH,
            Capability.HOSTED_MCP,
            Capability.HOSTED_TOOL_SEARCH)) transport.put(capability, Support.UNSUPPORTED);

    var declared = filled(Support.SUPPORTED);
    for (var capability :
        EnumSet.of(
            Capability.FUNCTION_CALLING,
            Capability.REASONING_REPLAY,
            Capability.REASONING_EFFORT,
            Capability.VISION,
            Capability.FILE_INPUT,
            Capability.STRUCTURED_OUTPUT,
            Capability.TEMPERATURE,
            Capability.TOP_P,
            Capability.HOSTED_WEB_SEARCH,
            Capability.REASONING_SUMMARY,
            Capability.TEXT_VERBOSITY,
            Capability.PARALLEL_TOOL_CALLS)) declared.put(capability, Support.UNKNOWN);
    if (server != null) {
      for (var setting : server.getSupportedSettings()) {
        var capability =
            switch (setting) {
              case IMAGE_INPUT -> Capability.VISION;
              case TEMPERATURE -> Capability.TEMPERATURE;
              case TOP_P -> Capability.TOP_P;
              case REASONING_EFFORT -> Capability.REASONING_EFFORT;
              case REASONING_SUMMARY -> Capability.REASONING_SUMMARY;
              case TEXT_VERBOSITY -> Capability.TEXT_VERBOSITY;
              case PARALLEL_TOOL_CALLS -> Capability.PARALLEL_TOOL_CALLS;
              case STRUCTURED_OUTPUT -> Capability.STRUCTURED_OUTPUT;
            };
        declared.put(capability, Support.SUPPORTED);
      }
      applyDeclarations(server.getModelCapabilities(), declared, field + ".model-capabilities");
    }
    var implementation = filled(named ? Support.UNKNOWN : Support.SUPPORTED);
    if (server != null)
      applyDeclarations(server.getServerCapabilities(), implementation, field + ".server-capabilities");
    return new LlmEndpointCapabilities(
        transport,
        implementation,
        declared,
        LlmCapabilities.resolveToolLimit(
            server, provider == LlmProvider.CHATGPT_PLAN ? null : defaultMaxTools));
  }

  private static void applyDeclarations(Map<String, String> source,
      Map<Capability, Support> declared, String field) {
      var seen = EnumSet.noneOf(Capability.class);
      for (var entry : source.entrySet()) {
        Capability capability;
        try {
          capability =
              Capability.valueOf(
                  entry.getKey().strip().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException invalid) {
          throw new IllegalArgumentException(field + ": unknown capability");
        }
        if (!seen.add(capability))
          throw new IllegalArgumentException(field + ": duplicate capability");
        String path =
            field
                + "."
                + capability.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        try {
          declared.put(
              capability,
              Support.valueOf(entry.getValue().strip().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException invalid) {
          throw new IllegalArgumentException(path + ": must be supported, unsupported, or unknown");
        }
      }
  }
}
