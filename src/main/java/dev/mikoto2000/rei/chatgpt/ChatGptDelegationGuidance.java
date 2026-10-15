package dev.mikoto2000.rei.chatgpt;

import dev.mikoto2000.rei.subagent.SubAgentRegistry;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Describes effective host configuration without sending credentials or contacting a provider. */
@Component
public final class ChatGptDelegationGuidance {
  private final ObjectProvider<PlanProperties> settings;
  private final ObjectProvider<PlanDelegationProperties> automatic;
  private final ObjectProvider<PlanOAuth> oauth;
  private final ObjectProvider<SubAgentRegistry> registry;

  public ChatGptDelegationGuidance(
      ObjectProvider<PlanProperties> settings,
      ObjectProvider<PlanDelegationProperties> automatic,
      ObjectProvider<PlanOAuth> oauth,
      ObjectProvider<SubAgentRegistry> registry) {
    this.settings = settings;
    this.automatic = automatic;
    this.oauth = oauth;
    this.registry = registry;
  }

  public String prompt() {
    var plan = settings.getIfAvailable();
    if (plan == null || !plan.enabled()) return unavailable("rei.chatgpt-plan.enabled is disabled");
    var delegation = automatic.getIfAvailable();
    if (delegation == null || !delegation.enabled())
      return unavailable(
          "rei.chatgpt-plan.auto-delegation.enabled is disabled; login alone does not enable automatic delegation");
    var agents = registry.getIfAvailable();
    List<String> roles =
        agents == null
            ? List.of()
            : plan.allowedRoles().stream()
                .filter(
                    id ->
                        agents
                            .findById(id)
                            .filter(
                                role ->
                                    role.requiredToolCalls().isEmpty()
                                        && role.evidenceTools().isEmpty()
                                        && !role.semanticValidation())
                            .isPresent())
                .filter(id -> id.matches("[A-Za-z0-9_-]{1,64}"))
                .sorted()
                .limit(16)
                .toList();
    var accounts =
        delegation.allowedAccounts().stream()
            .filter(id -> id.matches("[A-Za-z0-9_-]{1,64}"))
            .sorted()
            .limit(16)
            .toList();
    var models =
        delegation.allowedModels().stream()
            .filter(id -> id.matches("[A-Za-z0-9_.-]{1,128}"))
            .sorted()
            .limit(16)
            .toList();
    if (roles.isEmpty() || accounts.isEmpty() || models.isEmpty())
      return unavailable(
          "no eligible registered allowed role, allowed account or allowed model; check allowed-roles and auto-delegation allowlists");
    Set<String> authorized;
    try {
      var auth = oauth.getIfAvailable();
      authorized = auth == null ? Set.of() : auth.authorizedAccounts();
    } catch (RuntimeException error) {
      return unavailable("local authentication status could not be read; check /chatgpt status");
    }
    var ready = accounts.stream().filter(authorized::contains).toList();
    if (ready.isEmpty())
      return unavailable(
          "no allowed account has saved ChatGPT plan authorization; check /chatgpt status and /chatgpt login");
    String example = roles.contains("reviewer") ? "reviewer" : roles.getFirst();
    return "Current host ChatGPT delegation configuration (guidance, not authorization):\n"
        + "ChatGPT delegation is enabled with saved plan authorization. Use delegateTask or delegateTasks with backendId=chatgpt-plan.\n"
        + "agent (eligible allowed roles): "
        + roles
        + "; accountRef (allowed authorized accounts): "
        + ready
        + "; modelId (administrator-allowed models): "
        + models
        + ".\n"
        + "Copy accountRef and modelId exactly from these lists, preserving case and punctuation. Use the model ID, not its display name or a guessed alias. A model appearing in /chatgpt models is not automatically allowed for delegation; administrator-allowed models above are the permitted choices.\n"
        + "For a human request such as 'ChatGPT にレビューして', use this ChatGPT route, not requestCodexReview or Claude Code. Do not claim ChatGPT cannot be called.\n"
        + "ChatGPT delegation does not require durable SubAgents, DAGs or consensus features. Do not call listDurableSubAgents/getDurableSubAgent to discover ChatGPT configuration; the allowed account/model/role labels are listed above. sourceRefs are read by the host and supplied as document snapshots; ChatGPT does not read local files directly.\n"
        + "Use the original Project-relative sourceRefs. The host masks recognized credential values, JWTs and private keys only in the in-memory source snapshots; it does not change the original files or require manual copies. Each snapshot reports sources[i].redacted=true when masking occurred. Hidden values cannot be verified: mark relevant conclusions unverified and never guess or reconstruct them. references.sha256 and quote must match the supplied masked snapshot, not the original raw content. Task text and instructions remain subject to credential checks; masking only applies to source bodies.\n"
        + "Single-request example: {\"agent\":\""
        + example
        + "\",\"backendId\":\"chatgpt-plan\",\"accountRef\":\""
        + ready.getFirst()
        + "\",\"modelId\":\""
        + models.getFirst()
        + "\",\"task\":\"Review the supplied project documents\",\"sourceRefs\":[\"docs/design.md\"],\"expectedOutput\":\"{\\\"unverified\\\":[]}\"}.\n"
        + "The example path and expectedOutput are placeholders: use actual Project-relative sourceRefs (at most 16) and nonempty JSON result-field conditions matching the selected role schema. For delegateTasks wrap requests in requests:[...] and give each a unique id. Omit context for external delegation.\n"
        + "Supply the original sourceRefs, at most 16 files, each at most 262144 raw UTF-8 bytes. If aggregate raw/masked bytes or the complete encoded input exceeds its limit, the host recursively bisects the ordered file list, approves the exact whole plan once, and sends each group sequentially to the same account/model/role. Shared call/token/repair/deadline limits never reset. It stops on failure/partial/cancellation/unknown, returning validated Receipt.groups and remainingScopes. Cross-group relationships require independent parent verification. One file or instructions that cannot fit are refused with an actionable error; file contents are not split.\n"
        + "Child tools are not available. The current Run must be EXCLUSIVE and have shared budget; external-send permission and exact-call approval still apply. Never switch providers without human authorization.\n"
        + "expectedOutput is NOT JSON Schema or an output template: every specified result field must exactly equal the supplied literal JSON value. Never use string/high|medium|low as type placeholders. Put formatting instructions in task/constraints. reviewer result fields are findings (severity HIGH/MEDIUM/LOW, message, location) and unverified; no summary/file/description fields. {\"unverified\":[]} means there must be exactly zero unverified items; choose conditions appropriate to the task rather than forcing unknown facts to disappear.\n"
        + "Saved authentication does not guarantee a valid token, quota or model access. Models listed here are allowed by configuration, not a live catalog check; /chatgpt models --account ACCOUNT confirms access.";
  }

  private static String unavailable(String reason) {
    return "Current host ChatGPT delegation configuration: automatic delegation unavailable: "
        + reason
        + ". ChatGPT has its own chatgpt-plan route; it is distinct from Codex. Do not substitute Codex or Claude Code without an explicit human request for that provider.";
  }

  public String rejectionHint(String userRequest) {
    if (userRequest == null || !userRequest.toLowerCase(Locale.ROOT).matches("(?s).*chat\\s*gpt.*"))
      return "";
    return "\nThe human requested ChatGPT, not Codex/Claude Code. " + prompt();
  }
}
