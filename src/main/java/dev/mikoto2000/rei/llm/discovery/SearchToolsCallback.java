package dev.mikoto2000.rei.llm.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Searches and publishes in one local call. No model invocation and no business side effect. */
public final class SearchToolsCallback implements ToolCallback {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ToolCatalog catalog;
  private final int defaultLimit;
  private final Function<Set<String>, RunToolAvailability.Change> publish;

  public SearchToolsCallback(
      ToolCatalog catalog,
      int defaultLimit,
      Function<Set<String>, RunToolAvailability.Change> publish) {
    this.catalog = catalog;
    this.defaultLimit = defaultLimit;
    this.publish = publish;
  }

  public ToolDefinition getToolDefinition() {
    return ToolDefinition.builder()
        .name("searchTools")
        .description(
            "Find specialist tools by exact name or Japanese/English purpose and add the matching"
                + " operation sets. Added tools are available from the NEXT model request only."
                + " Call this alone before calling a currently unpublished tool. This does not"
                + " grant permissions or perform the requested business operation.")
        .inputSchema(
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":256},\"maxResults\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":5}},\"required\":[\"query\"],\"additionalProperties\":false}")
        .build();
  }

  public String call(String input) {
    try {
      var args = JSON.readTree(input);
      if (args == null || !args.isObject() || !args.path("query").isTextual())
        return error("invalid_arguments");
      var fields = args.fieldNames();
      while (fields.hasNext())
        if (!Set.of("query", "maxResults").contains(fields.next()))
          return error("invalid_arguments");
      if (args.has("maxResults")
          && (!args.get("maxResults").isIntegralNumber()
              || !args.get("maxResults").canConvertToInt())) return error("invalid_arguments");
      int limit = args.has("maxResults") ? args.get("maxResults").intValue() : defaultLimit;
      var sets = new ToolSearchService().search(catalog, args.get("query").textValue(), limit);
      if (sets.isEmpty()) return error("no_match");
      var change = publish.apply(Set.copyOf(sets));
      var result = new LinkedHashMap<String, Object>();
      result.put("status", change.status());
      result.put("operationSets", sets);
      result.put(
          "matches",
          sets.stream()
              .map(set -> {
                var entry =
                    catalog.searchable().stream()
                        .filter(e -> !e.basic() && e.set().equals(set))
                        .min(java.util.Comparator.comparing(ToolCatalog.Entry::name))
                        .orElseThrow();
                String description =
                    catalog.callback(entry.name()).getToolDefinition().description();
                return Map.of(
                    "operationSet",
                    set,
                    "tool",
                    entry.name(),
                    "purpose",
                    description.substring(0, Math.min(description.length(), 160)));
              })
              .toList());
      result.put("added", change.added());
      result.put("removed", change.removed());
      result.put("toolsetVersion", change.toolsetVersion());
      result.put("activeToolCount", change.activeToolCount());
      result.put("maxToolCount", change.maxToolCount());
      result.put("requiredToolCount", change.requiredToolCount());
      result.put("protectedTools", change.protectedTools());
      var already = new java.util.TreeSet<>(catalog.toolsForSets(Set.copyOf(sets)));
      already.removeAll(change.added());
      if (change.status().equals("added") || change.status().equals("already_available"))
        result.put("alreadyAvailable", List.copyOf(already));
      result.put("availableFrom", "next_model_request");
      if (change.status().equals("selection_too_large"))
        result.put(
            "hint",
            "Retry with maxResults=1 and a narrower purpose or exact tool name from matches.");
      if (change.status().equals("capacity_exceeded"))
        result.put(
            "hint",
            "Complete or explicitly cancel retained work using its existing tools before adding"
                + " another operation set. No visibility was changed.");
      return JSON.writeValueAsString(result);
    } catch (com.fasterxml.jackson.core.JsonProcessingException
        | IllegalArgumentException invalid) {
      return error("invalid_arguments");
    }
  }

  private String error(String code) {
    return "{\"status\":\"" + code + "\"}";
  }

  @Override
  public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
    return call(input);
  }
}
