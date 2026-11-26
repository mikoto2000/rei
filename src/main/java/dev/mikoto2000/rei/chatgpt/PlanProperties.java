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
 * @param maxConcurrent 登録ごとの同時推論要求数
 */
@ConfigurationProperties(prefix = "rei.chatgpt-plan")
public record PlanProperties(
    boolean enabled, Path credentialDirectory, Set<String> allowedRoles, Integer maxConcurrent) {
  /** Retains the existing registration settings constructor with conservative request capacity. */
  public PlanProperties(boolean enabled, Path credentialDirectory, Set<String> allowedRoles) {
    this(enabled, credentialDirectory, allowedRoles, 1);
  }

  /** 受け取った情報からPlanPropertiesを作成する。 */
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public PlanProperties {
    if (maxConcurrent == null) maxConcurrent = 1;
    if (maxConcurrent < 1 || maxConcurrent > 8)
      throw new IllegalArgumentException("rei.chatgpt-plan.max-concurrent must be between 1 and 8");
    if (credentialDirectory == null)
      credentialDirectory =
          dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
              .resolve("credentials/chatgpt-plan");
    allowedRoles = allowedRoles == null ? Set.of() : Set.copyOf(allowedRoles);
  }
}
