package dev.mikoto2000.rei.subagent;

import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;

/** Resolve host-controlled child definitions, never capabilities claimed in model arguments. */
@Component
public final class DelegationToolPermissionResolver {
  private final ObjectProvider<SubAgentRegistry> registry;
  private final ObjectProvider<SubAgentToolPolicy> childPolicy;
  private final JsonMapper json = JsonMapper.builder().build();
  private dev.mikoto2000.rei.chatgpt.PlanDelegationProperties automatic;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setAutomatic(dev.mikoto2000.rei.chatgpt.PlanDelegationProperties automatic){this.automatic=automatic;}
  public DelegationToolPermissionResolver(ObjectProvider<SubAgentRegistry> registry,
      ObjectProvider<SubAgentToolPolicy> childPolicy) {
    this.registry = registry; this.childPolicy = childPolicy;
  }
  public static boolean supports(String tool) {
    return Set.of("delegateTask", "delegateTasks", "delegateTaskGraph", "judgeSubAgentAnswers").contains(tool);
  }
  public void validate(String tool,String input) {
    if(!Set.of("delegateTask","delegateTasks").contains(tool))return;
    DelegationArguments.validate(tool,input);
    var root=json.readTree(input);
    Iterable<JsonNode> requests="delegateTasks".equals(tool)?root.path("requests"):List.of(root);
    for(var request:requests) {
      var definition=registry.getObject().findById(request.path("agent").asString())
          .orElseThrow(()->new IllegalArgumentException("Invalid delegation arguments: unavailable agent"));
      childPolicy.getObject().validate(definition.requestedTools());
      String backend=DelegationArguments.optional(request.path("backendId").asString(""));
      if(backend!=null && !backend.equals("local"))ExpectedOutputConditions.validate(request.path("expectedOutput").asString(),definition.resultSchema());
      if(backend!=null && !backend.equals("local") && automatic!=null)automatic.check(request.path("accountRef").asString(),request.path("modelId").asString());
    }
  }
  public Set<ActionCapability> resolve(String tool, String input,
      Function<String, Set<ActionCapability>> capabilities) {
    try {
      var root = json.readTree(input);
      if (root == null || !root.isObject()) return all();
      var required = EnumSet.of(ActionCapability.READ);
      if ("delegateTasks".equals(tool)) {
        var requests = root.path("requests");
        if (!requests.isArray() || requests.isEmpty() || requests.size() > 8) return all();
        for (var request : requests) required.addAll(child(request, capabilities));
      } else if ("delegateTaskGraph".equals(tool)) {
        var nodes = root.path("spec").path("nodes");
        if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > 16) return all();
        required.add(ActionCapability.LOCAL_WRITE);
        for (var node : nodes) {
          if (!node.path("backendId").isMissingNode()) return all();
          required.addAll(child(node, capabilities));
        }
      } else {
        if ("judgeSubAgentAnswers".equals(tool) && !root.path("backendId").isMissingNode()) return all();
        required.addAll(child(root, capabilities));
      }
      return Set.copyOf(required);
    } catch (RuntimeException error) {
      // Unavailable definitions, malformed JSON and unknown destinations never gain a read grant.
      return all();
    }
  }
  private Set<ActionCapability> child(JsonNode request,
      Function<String, Set<ActionCapability>> capabilities) {
    if (!request.isObject() || !request.path("agent").isString()) return all();
    var definition = registry.getObject().findById(request.path("agent").asString()).orElseThrow();
    var policy = childPolicy.getObject();
    policy.validate(definition.requestedTools());
    var required = EnumSet.of(ActionCapability.READ);
    for (var tool : policy.effectiveTools(definition.requestedTools())) {
      required.addAll(ToolPermissionPolicy.intrinsicCapabilities(tool));
      required.addAll(capabilities.apply(tool));
    }
    var backend = request.path("backendId");
    if (!backend.isMissingNode() && !backend.isNull() && !backend.isString()) return all();
    switch (backend.isMissingNode() || backend.isNull() || backend.asString().isBlank() ? "local" : backend.asString()) {
      case "local" -> { }
      case "chatgpt-plan" -> {
        required.add(ActionCapability.NETWORK_WRITE);
        required.add(ActionCapability.EXTERNAL_SIDE_EFFECT);
        required.addAll(capabilities.apply("requestChatGptPlanSubAgent"));
      }
      case "codex-app-server" -> {
        required.add(ActionCapability.NETWORK_WRITE);
        required.add(ActionCapability.EXTERNAL_SIDE_EFFECT);
        required.add(ActionCapability.EXECUTE);
        required.addAll(capabilities.apply("requestCodexAppServerSubAgent"));
      }
      default -> { return all(); }
    }
    return required;
  }
  private static Set<ActionCapability> all() { return Set.copyOf(EnumSet.allOf(ActionCapability.class)); }
}
