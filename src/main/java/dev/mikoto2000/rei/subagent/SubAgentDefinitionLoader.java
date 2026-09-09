package dev.mikoto2000.rei.subagent;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Safe scalar/map YAML only. Diagnostics never echo arbitrary YAML or prompts. */
public final class SubAgentDefinitionLoader {
  private static final Set<String> FIELDS = Set.of("id", "name", "description", "systemPrompt", "tools", "model", "maxSteps", "timeout");
  private final SubAgentToolPolicy policy;
  private final Predicate<String> modelResolver;
  public SubAgentDefinitionLoader(SubAgentToolPolicy policy, Predicate<String> modelResolver) {
    this.policy = policy;
    this.modelResolver = modelResolver;
  }
  public SubAgentDefinition load(Path file) {
    Map<?, ?> values;
    try {
      if (Files.size(file) > 262144) throw new IllegalArgumentException("YAML exceeds 256 KiB");
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      options.setMaxAliasesForCollections(0);
      options.setCodePointLimit(262144);
      options.setNestingDepthLimit(10);
      Object document = new Yaml(new SafeConstructor(options)).load(Files.readString(file));
      if (!(document instanceof Map<?, ?> map)) throw new IllegalArgumentException("expected a mapping");
      values = map;
    } catch (Exception error) {
      throw invalid(file, "YAML: invalid mapping, duplicate key, unsafe tag, unreadable or oversized file");
    }
    try {
      for (Object key : values.keySet()) if (!(key instanceof String) || !FIELDS.contains(key)) {
        String label = key instanceof String text && text.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") ? text : "(invalid key)";
        throw new IllegalArgumentException("YAML: unknown configuration field: " + label);
      }
      List<String> tools = new ArrayList<>();
      if (values.containsKey("tools")) {
        if (!(values.get("tools") instanceof List<?> list)) throw new IllegalArgumentException("tools: expected list");
        for (Object value : list) {
          if (!(value instanceof String tool) || !tool.matches("[A-Za-z][A-Za-z0-9_]{0,99}"))
            throw new IllegalArgumentException("tools: invalid tool name");
          tools.add(tool);
        }
      }
      policy.validate(tools);
      String model = values.containsKey("model") ? text(values, "model") : null;
      if (model != null && !modelResolver.test(model)) throw new IllegalArgumentException("model: cannot resolve configured model");
      Object steps = values.get("maxSteps");
      if (!(steps instanceof Integer count)) throw new IllegalArgumentException("maxSteps: required positive integer");
      Duration timeout;
      try {
        String value = text(values, "timeout");
        if (!value.matches("[0-9]+(ms|s|m|h)")) throw new IllegalArgumentException();
        int suffix = value.endsWith("ms") ? 2 : 1;
        long amount = Long.parseLong(value.substring(0, value.length() - suffix));
        timeout = switch (value.substring(value.length() - suffix)) {
          case "ms" -> Duration.ofMillis(amount);
          case "s" -> Duration.ofSeconds(amount);
          case "m" -> Duration.ofMinutes(amount);
          default -> Duration.ofHours(amount);
        };
      } catch (Exception e) { throw new IllegalArgumentException("timeout: required positive duration (120s, 2m, 1h, 500ms)"); }
      return new SubAgentDefinition(text(values, "id"), text(values, "name"), text(values, "description"),
          text(values, "systemPrompt"), tools, model, count, timeout, file);
    } catch (IllegalArgumentException error) { throw invalid(file, error.getMessage()); }
  }
  private String text(Map<?, ?> values, String key) {
    if (!(values.get(key) instanceof String value) || value.isBlank()) throw new IllegalArgumentException(key + ": required nonblank string");
    return value;
  }
  private IllegalArgumentException invalid(Path file, String reason) {
    return new IllegalArgumentException(file.toAbsolutePath().normalize() + ": " + reason);
  }
}
