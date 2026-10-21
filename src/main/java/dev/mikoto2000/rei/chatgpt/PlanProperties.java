package dev.mikoto2000.rei.chatgpt;

import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ChatGPTとの連携で受け渡す計画設定を表す。
 *
 * @param enabled 有効状態
 * @param credentialDirectory 認証情報ディレクトリ
 * @param allowedRoles 許可役割一覧
 */
@ConfigurationProperties(prefix = "rei.chatgpt-plan")
public record PlanProperties(boolean enabled, Path credentialDirectory, Set<String> allowedRoles) {
  /** 受け取った情報からPlanPropertiesを作成する。 */
  public PlanProperties {
    if (credentialDirectory == null)
      credentialDirectory =
          dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
              .resolve("credentials/chatgpt-plan");
    allowedRoles = allowedRoles == null ? Set.of() : Set.copyOf(allowedRoles);
  }
}
