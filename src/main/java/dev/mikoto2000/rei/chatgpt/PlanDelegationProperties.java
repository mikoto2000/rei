package dev.mikoto2000.rei.chatgpt;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Administrator-owned destinations; model input can never expand these grants. */
@ConfigurationProperties(prefix = "rei.chatgpt-plan.auto-delegation")
public record PlanDelegationProperties(boolean enabled, Set<String> allowedAccounts,
    Set<String> allowedModels) {
  public PlanDelegationProperties {
    allowedAccounts = allowedAccounts == null ? Set.of() : Set.copyOf(allowedAccounts);
    allowedModels = allowedModels == null ? Set.of() : Set.copyOf(allowedModels);
  }
  public void check(String account, String model) {
    if(!enabled)throw ChatGptException.permission(ChatGptException.PermissionReason.AUTO_DELEGATION_DISABLED);
    if(account==null||account.isBlank())throw ChatGptException.permission(ChatGptException.PermissionReason.ACCOUNT_REQUIRED);
    if(model==null||model.isBlank())throw ChatGptException.permission(ChatGptException.PermissionReason.MODEL_REQUIRED);
    if(!allowedAccounts.contains(account))throw ChatGptException.permission(ChatGptException.PermissionReason.ACCOUNT_NOT_ALLOWED);
    if(!allowedModels.contains(model))throw ChatGptException.permission(ChatGptException.PermissionReason.MODEL_NOT_ALLOWED);
  }
}
