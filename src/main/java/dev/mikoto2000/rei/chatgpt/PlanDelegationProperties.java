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
    if (!enabled || account == null || model == null
        || !allowedAccounts.contains(account) || !allowedModels.contains(model))
      throw ChatGptException.of(ChatGptException.Code.PERMISSION_DENIED);
  }
}
