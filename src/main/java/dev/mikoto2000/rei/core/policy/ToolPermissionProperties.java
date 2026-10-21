package dev.mikoto2000.rei.core.policy;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ツール利用の権限制御で受け渡すツール権限設定を表す。
 *
 * @param enabled 有効状態
 * @param autoApprove 自動承認
 * @param denied 拒否
 * @param capabilities 許可された操作一覧
 */
@ConfigurationProperties("rei.tool-permission")
public record ToolPermissionProperties(
    boolean enabled,
    Set<ActionCapability> autoApprove,
    Set<ActionCapability> denied,
    Map<String, Set<ActionCapability>> capabilities) {
  /** 受け取った情報からToolPermissionPropertiesを作成する。 */
  public ToolPermissionProperties {
    autoApprove = autoApprove == null ? Set.of(ActionCapability.READ) : Set.copyOf(autoApprove);
    denied = denied == null ? Set.of(ActionCapability.DESTRUCTIVE) : Set.copyOf(denied);
    var copy = new HashMap<String, Set<ActionCapability>>();
    if (capabilities != null)
      capabilities.forEach(
          (name, values) -> {
            if (name == null || name.isBlank() || values == null || values.isEmpty())
              throw new IllegalArgumentException(
                  "Tool capability classification must not be empty");
            copy.put(name, Set.copyOf(values));
          });
    capabilities = Map.copyOf(copy);
  }
}
