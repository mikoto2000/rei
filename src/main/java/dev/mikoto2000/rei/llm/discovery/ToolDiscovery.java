package dev.mikoto2000.rei.llm.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Connects discovery to the existing run loop; owns no global mutable tool visibility. */
@Component
@EnableConfigurationProperties(ToolDiscoveryProperties.class)
public final class ToolDiscovery {
  public static final String SNAPSHOT_KEY = ToolDiscovery.class.getName() + ".snapshot";

  public record Snapshot(String requestId, long version, Set<String> names) {
    public Snapshot {
      names = Set.copyOf(names);
    }
  }

  private final boolean discoveryEnabled;
  private final int maxActiveTools;
  private final int searchMaxResults;
  private final int maxAddedToolsPerSearch;
  private final ObjectProvider<ToolDiscoveryStore> stores;
  private org.springframework.core.env.Environment environment;

  @org.springframework.beans.factory.annotation.Autowired
  void configureEnvironment(org.springframework.core.env.Environment value) {
    environment = value;
  }

  private boolean featureAvailable(String name) {
    if (environment == null) return true;
    String key =
        switch (name) {
          case "searchPapers",
              "searchPaperLibrary",
              "getPaper",
              "getPaperContent",
              "getPaperSection",
              "getPaperTranslationChunk",
              "summarizePaper",
              "translatePaper" ->
              "rei.paper.enabled";
          case "proposeDiagnosedRepair", "inspectDiagnosedRepair", "applyDiagnosedRepair" ->
              "rei.repair.diagnosed-enabled";
          case "validateDocumentRender", "inspectDocumentRender" -> "rei.document-renderer.enabled";
          case "reviewPatchRequirements",
              "getPatchRequirementReview",
              "inspectPatchRequirementReview",
              "listPatchRequirementReviews" ->
              "rei.patch-review.semantic-enabled";
          default -> null;
        };
    return key == null
        || environment.getProperty(key, Boolean.class, key.equals("rei.paper.enabled"));
  }

  public ToolDiscovery(
      ToolDiscoveryProperties properties, ObjectProvider<ToolDiscoveryStore> stores) {
    this.discoveryEnabled = properties.isEnabled();
    this.maxActiveTools = properties.getMaxActiveTools();
    this.searchMaxResults = properties.getSearchMaxResults();
    this.maxAddedToolsPerSearch = properties.getMaxAddedToolsPerSearch();
    this.stores = stores;
  }

  public boolean enabled() {
    return discoveryEnabled;
  }

  public void validateOutbound(Prompt prompt) {
    ToolRequestValidator.validate(prompt, maxActiveTools);
  }

  public void inherit(AgentRunContext owner, String previous) {
    if (enabled()) stores.getObject().inherit(owner, previous);
  }

  public Session start(
      Prompt prompt, RunExecutionContext execution, UnaryOperator<ToolCallback> observe) {
    if (!enabled()
        || execution.runContext() == null
        || execution.runContext().mode() == AgentRunContext.Mode.CONVERSATION) return null;
    return new Session(prompt, execution, observe, stores.getIfAvailable());
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public final class Session {
    private final ToolCatalog catalog;
    private final RunToolAvailability availability;
    private final RunExecutionContext execution;
    private final AgentRunContext owner;
    private final ToolDiscoveryStore store;
    private final ToolCallback search;
    private final String endpoint;
    private final Map<String, Set<String>> references = new TreeMap<>();
    private final Set<String> unresolved = new TreeSet<>();
    private Map<String, String> requestDefinitions = Map.of();
    private Set<String> currentlyAllowed = Set.of();
    private java.util.function.Supplier<Set<String>> viewRetention = Set::of;
    private final ObjectMapper json = new ObjectMapper();
    private final int maximum;

    private Session(
        Prompt initial,
        RunExecutionContext execution,
        UnaryOperator<ToolCallback> observe,
        ToolDiscoveryStore store) {
      this.execution = execution;
      this.owner = Objects.requireNonNull(execution.runContext(), "Run owner required");
      this.store = store;
      this.maximum = maxActiveTools;
      var options = explicitOptions(initial);
      var supplied =
          Objects.requireNonNull(options.getToolCallbacks(), "Explicit callbacks required").stream()
              .filter(callback -> featureAvailable(callback.getToolDefinition().name()))
              .filter(
                  callback ->
                      owner.mode() != AgentRunContext.Mode.READ_ONLY
                          || dev.mikoto2000.rei.core.policy.ToolPermissionPolicy
                              .intrinsicallyReadOnly(callback.getToolDefinition().name()))
              .toList();
      this.catalog = ToolCatalog.standard(supplied);
      if (owner.mode() == AgentRunContext.Mode.EXCLUSIVE && catalog.basicNames().size() != 31)
        throw new IllegalStateException(
            "Tool discovery requires all 31 basic tools in exclusive mode");
      this.availability = new RunToolAvailability(catalog, maximum, maxAddedToolsPerSearch);
      this.endpoint = endpointFingerprint(initial);
      this.search =
          observe.apply(new SearchToolsCallback(catalog, searchMaxResults, this::publish));
      availability.setRetention(this::retainedNames);
      if (store != null) store.load(owner).ifPresent(this::restore);
      // Validate ledger access before the first resumed HTTP request, not after a side effect.
      if (!availability.names().containsAll(retainedNames()))
        throw new IllegalStateException("Retained tools are missing from restored visibility");
    }

    private synchronized void restore(ToolDiscoveryStore.Saved saved) {
      if (!saved.endpoint().equals(endpoint))
        throw new IllegalStateException(
            "Saved tool endpoint or mode is incompatible; explicitly review the resume"
                + " configuration");
      saved
          .definitions()
          .forEach(
              (name, digest) -> {
                if (!name.equals("searchTools") && !catalog.names().contains(name))
                  throw new IllegalStateException(
                      "Saved tool unavailable after feature or permission change: " + name);
                if (!definitionHash(name).equals(digest))
                  throw new IllegalStateException("Saved tool definition is incompatible: " + name);
              });
      references.putAll(saved.references());
      unresolved.addAll(saved.unresolved());
      requestDefinitions = saved.requestDefinitions();
      availability.restore(saved.availability());
      if (!saved.definitions().keySet().equals(availability.names()))
        throw new IllegalStateException("Saved tool definitions do not match restored visibility");
    }

    private ToolCallingChatOptions explicitOptions(Prompt prompt) {
      if (!(prompt.getOptions() instanceof ToolCallingChatOptions options))
        throw new IllegalArgumentException("Explicit tool options required");
      Objects.requireNonNull(options.getToolCallbacks(), "Explicit callbacks required");
      return options;
    }

    private String definitionHash(String name) {
      ToolDefinition d =
          name.equals("searchTools")
              ? search.getToolDefinition()
              : catalog.callback(name).getToolDefinition();
      return hash(d.name() + "\n" + d.description() + "\n" + d.inputSchema());
    }

    private String endpointFingerprint(Prompt prompt) {
      var connectionOptions =
          Objects.requireNonNull(prompt.getOptions(), "Explicit model options required");
      String connection = Objects.toString(connectionOptions.getModel(), "");
      if (prompt.getOptions() instanceof org.springframework.ai.openai.OpenAiChatOptions o)
        connection +=
            "|" + Objects.toString(o.getBaseUrl(), "") + "|" + Objects.toString(o.getTimeout(), "");
      return hash(connection + "|" + owner.mode());
    }

    private synchronized RunToolAvailability.Change publish(Set<String> sets) {
      execution.checkActive();
      if (!currentlyAllowed.containsAll(catalog.toolsForSets(sets))) {
        var before = availability.saved();
        return new RunToolAvailability.Change(
            "unavailable",
            List.of(),
            List.of(),
            before.version(),
            availability.names().size(),
            maximum,
            availability.names().size(),
            List.of());
      }
      var before = availability.saved();
      var result = availability.add(sets);
      try {
        save();
        return result;
      } catch (RuntimeException error) {
        availability.restore(before);
        throw new IllegalStateException(
            "Could not persist tool visibility; previous state restored", error);
      }
    }

    private synchronized Set<String> retainedNames() {
      var sets = new TreeSet<String>();
      unresolved.forEach(reference -> sets.add(reference.split("@", 2)[0]));
      if (store != null) {
        var required = new TreeMap<String, Set<String>>();
        references.forEach(
            (set, ids) -> {
              var pending = new TreeSet<String>();
              for (String id : ids) if (store.required(owner, set, id)) pending.add(id);
              if (!pending.isEmpty()) {
                required.put(set, Set.copyOf(pending));
                sets.add(set);
              }
            });
        // Commit the projection only after all reads succeed. The original ledgers remain
        // authoritative.
        references.clear();
        references.putAll(required);
      }
      var names = new TreeSet<>(catalog.toolsForSets(sets));
      names.addAll(viewRetention.get());
      return Set.copyOf(names);
    }

    public synchronized void retainView(java.util.function.Supplier<Set<String>> required) {
      viewRetention = Objects.requireNonNull(required);
    }

    public synchronized Prompt project(Prompt prompt) {
      execution.checkActive();
      if (!endpoint.equals(endpointFingerprint(prompt)))
        throw new IllegalStateException("Endpoint configuration changed during the Run");
      Set<String> allowed =
          Objects.requireNonNull(
                  explicitOptions(prompt).getToolCallbacks(), "Explicit callbacks required")
              .stream()
              .map(c -> c.getToolDefinition().name())
              .collect(java.util.stream.Collectors.toSet());
      currentlyAllowed = Set.copyOf(allowed);
      Prompt result =
          ToolRequestAssembler.assemble(
              prompt,
              catalog,
              availability,
              search,
              name -> name.equals("searchTools") || allowed.contains(name),
              maximum);
      var opts = explicitOptions(result);
      var context = new HashMap<String, Object>();
      var existingContext = opts.getToolContext();
      if (existingContext != null) context.putAll(existingContext);
      context.put(
          SNAPSHOT_KEY,
          new Snapshot(
              UUID.randomUUID().toString(),
              availability.saved().version(),
              Objects.requireNonNull(opts.getToolCallbacks(), "Explicit callbacks required")
                  .stream()
                  .map(c -> c.getToolDefinition().name())
                  .collect(java.util.stream.Collectors.toSet())));
      result = new Prompt(result.getInstructions(), opts.mutate().toolContext(context).build());
      var sentDefinitions = new TreeMap<String, String>();
      Objects.requireNonNull(opts.getToolCallbacks(), "Explicit callbacks required")
          .forEach(
              c ->
                  sentDefinitions.put(
                      c.getToolDefinition().name(),
                      hash(
                          c.getToolDefinition().name()
                              + "\n"
                              + c.getToolDefinition().description()
                              + "\n"
                              + c.getToolDefinition().inputSchema())));
      requestDefinitions = Map.copyOf(sentDefinitions);
      save();
      return result;
    }

    public synchronized void validatePending(List<String> names) {
      if (requestDefinitions.isEmpty())
        throw new IllegalStateException(
            "No saved tool snapshot for pending calls; review the legacy continuation before"
                + " enabling discovery");
      for (String name : names) {
        if (!requestDefinitions.containsKey(name)
            || !requestDefinitions.get(name).equals(definitionHash(name)))
          throw new IllegalStateException(
              "Pending call was not published in the saved request: " + name);
      }
    }

    public synchronized AutoCloseable protect(Prompt prompt, ChatResponse response) {
      var opts = explicitOptions(prompt);
      var snapshot =
          (Snapshot)
              Objects.requireNonNull(opts.getToolContext(), "Tool context required")
                  .get(SNAPSHOT_KEY);
      if (snapshot == null) throw new IllegalStateException("Missing request tool snapshot");
      var names =
          Objects.requireNonNull(response.getResult(), "Tool response generation required")
              .getOutput()
              .getToolCalls()
              .stream()
              .map(c -> c.name())
              .collect(java.util.stream.Collectors.toSet());
      // Existing ToolLoopSupport rejects the whole batch before execution.
      if (!snapshot.names().containsAll(names)) return () -> {};
      var lease = availability.protect(names);
      save();
      return lease;
    }

    private synchronized void save() {
      if (store == null) return;
      var definitions = new TreeMap<String, String>();
      availability.names().forEach(n -> definitions.put(n, definitionHash(n)));
      store.save(
          owner,
          new ToolDiscoveryStore.Saved(
              availability.saved(),
              definitions,
              endpoint,
              references,
              unresolved,
              requestDefinitions));
    }

    private String statefulSet(String name) {
      if (!catalog.names().contains(name)) return null;
      String set = catalog.operationSetFor(name);
      return Set.of(
                  "text-change",
                  "document-change",
                  "diagnosed-repair",
                  "review-legacy",
                  "review-durable",
                  "external-implementation",
                  "external-codex-review",
                  "external-claude-review",
                  "subagent",
                  "dependency")
              .contains(set)
          ? set
          : null;
    }

    public synchronized void beforeCall(String name, String input, String callId) {
      String set = statefulSet(name);
      if (set == null) return;
      var ids = new TreeSet<String>();
      collectIds(input, ids, set);
      if (!ids.isEmpty()) {
        var merged = new TreeSet<>(references.getOrDefault(set, Set.of()));
        merged.addAll(ids);
        references.put(set, Set.copyOf(merged));
      } else if (Set.of(
              "proposeTextChangeSet",
              "proposeTextDocumentChangeSet",
              "proposeDiagnosedRepair",
              "beginReview",
              "beginRepositoryReview",
              "requestCodexReview",
              "requestClaudeCodeReview",
              "requestParallelCodexReviews",
              "requestParallelClaudeCodeReviews",
              "prepareCodexImplementation",
              "requestCodexImplementation",
              "delegateTask",
              "delegateTasks",
              "registerDependency",
              "waitForHttpDependency",
              "waitForFile")
          .contains(name)) {
        unresolved.add(set + "@" + callId);
      }
      save();
    }

    public synchronized void afterCall(String name, String input, String result, String callId) {
      String set = statefulSet(name);
      if (set == null) return;
      var ids = new TreeSet<String>();
      collectIds(input, ids, set);
      if (!name.startsWith("list")) collectIds(result, ids, set);
      if (!ids.isEmpty()) {
        var merged = new TreeSet<>(references.getOrDefault(set, Set.of()));
        merged.addAll(ids);
        references.put(set, Set.copyOf(merged));
        unresolved.remove(set + "@" + callId);
      }
      save();
    }

    public Set<String> basicNames() {
      return catalog.basicNames();
    }

    private void collectIds(String payload, Set<String> ids, String set) {
      try {
        collectIds(json.readTree(payload), ids, set, 0);
      } catch (java.io.IOException ignored) {
        /* Paged/unparseable receipts remain conservatively retained. */
      }
    }

    private void collectIds(JsonNode node, Set<String> ids, String set, int depth) {
      if (node == null || depth > 5 || ids.size() >= 128) return;
      if (node.isArray()) {
        for (var n : node) collectIds(n, ids, set, depth + 1);
        return;
      }
      if (!node.isObject()) return;
      List<String> fields =
          switch (set) {
            case "review-durable" ->
                node.has("enumeration") ? List.of("id", "taskId") : List.of("taskId");
            case "review-legacy" ->
                node.has("ranges") && node.has("targets")
                    ? List.of("id", "reviewJobId")
                    : List.of("reviewJobId", "job");
            case "subagent" ->
                node.has("operations")
                    ? List.of("id", "childId", "durableTaskId")
                    : List.of("childId", "durableTaskId");
            case "external-implementation" -> List.of("requestId");
            case "external-codex-review", "external-claude-review" ->
                node.has("target")
                    ? List.of("id", "reviewId", "previousReviewId")
                    : List.of("reviewId", "previousReviewId");
            default -> List.of("id", "dependencyId", "changeSetId");
          };
      for (String field : fields) {
        var id = node.get(field);
        if (id != null
            && id.isTextual()
            && id.textValue().length() <= 256
            && !id.textValue().isBlank()) ids.add(id.textValue());
      }
      // Only known receipt envelopes; never arbitrary source text or instructions.
      for (String field : List.of("result", "receipt", "job", "checkpoint", "children"))
        if (node.has(field)) collectIds(node.get(field), ids, set, depth + 1);
    }
  }
}
