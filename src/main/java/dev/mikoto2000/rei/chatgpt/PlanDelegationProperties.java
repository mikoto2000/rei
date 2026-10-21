package dev.mikoto2000.rei.chatgpt;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ChatGPTとの連携で受け渡す計画委譲設定を表す。
 *
 * <p>Administrator-owned destinations; model input can never expand these grants.
 *
 * @param enabled 有効状態
 * @param allowedAccounts 許可アカウント一覧
 * @param allowedModels 許可モデル一覧
 */
@ConfigurationProperties(prefix = "rei.chatgpt-plan.auto-delegation")
public record PlanDelegationProperties(
    boolean enabled, Set<String> allowedAccounts, Set<String> allowedModels) {
  /** 受け取った情報からPlanDelegationPropertiesを作成する。 */
  public PlanDelegationProperties {
    allowedAccounts = allowedAccounts == null ? Set.of() : Set.copyOf(allowedAccounts);
    allowedModels = allowedModels == null ? Set.of() : Set.copyOf(allowedModels);
  }

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  public void check(String account, String model) {
    if (!enabled)
      throw ChatGptException.permission(ChatGptException.PermissionReason.AUTO_DELEGATION_DISABLED);
    if (account == null || account.isBlank())
      throw ChatGptException.permission(ChatGptException.PermissionReason.ACCOUNT_REQUIRED);
    if (model == null || model.isBlank())
      throw ChatGptException.permission(ChatGptException.PermissionReason.MODEL_REQUIRED);
    if (!allowedAccounts.contains(account))
      throw ChatGptException.permission(ChatGptException.PermissionReason.ACCOUNT_NOT_ALLOWED);
    if (!allowedModels.contains(model))
      throw ChatGptException.permission(ChatGptException.PermissionReason.MODEL_NOT_ALLOWED);
  }
}
