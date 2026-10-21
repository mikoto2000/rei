package dev.mikoto2000.rei.dot;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 利用者の状態表示で受け渡す状態表示設定を表す。
 *
 * @param enabled 有効状態
 * @param parentLlmEnabled 親言語モデル有効状態
 * @param autoResume 自動再開
 * @param allowedProjects 許可プロジェクト一覧
 * @param allowedCategories 許可分類一覧
 * @param activeLimit 活動中上限
 * @param queueLimit 実行待ち上限
 * @param defaultTimeout 既定タイムアウト
 * @param claimLease 利用権の確保利用権
 * @param resultRetentionDays 結果保持期間日数
 * @param requestRetentionDays 要求保持期間日数
 */
@ConfigurationProperties("rei.dot-delegation")
public record DotProperties(
    boolean enabled,
    boolean parentLlmEnabled,
    boolean autoResume,
    Set<String> allowedProjects,
    Set<String> allowedCategories,
    int activeLimit,
    int queueLimit,
    Duration defaultTimeout,
    Duration claimLease,
    int resultRetentionDays,
    int requestRetentionDays) {
  /** 受け取った情報からDotPropertiesを作成する。 */
  public DotProperties {
    allowedProjects = allowedProjects == null ? Set.of() : Set.copyOf(allowedProjects);
    allowedCategories =
        allowedCategories == null
            ? Set.of("TECH_RESEARCH", "COMPARISON", "REVIEW")
            : Set.copyOf(allowedCategories);
    if (activeLimit == 0) activeLimit = 2;
    if (queueLimit == 0) queueLimit = 8;
    if (defaultTimeout == null) defaultTimeout = Duration.ofHours(1);
    if (claimLease == null) claimLease = Duration.ofMinutes(30);
    if (resultRetentionDays == 0) resultRetentionDays = 30;
    if (requestRetentionDays == 0) requestRetentionDays = 90;
    if (activeLimit < 1
        || activeLimit > 2
        || queueLimit < 1
        || queueLimit > 8
        || defaultTimeout.compareTo(Duration.ofSeconds(1)) < 0
        || defaultTimeout.compareTo(Duration.ofHours(24)) > 0
        || claimLease.compareTo(Duration.ofSeconds(1)) < 0
        || claimLease.compareTo(Duration.ofMinutes(30)) > 0
        || resultRetentionDays < 1
        || resultRetentionDays > 30
        || requestRetentionDays < 1
        || requestRetentionDays > 90
        || allowedProjects.size() > 100
        || !Set.of("TECH_RESEARCH", "COMPARISON", "REVIEW").containsAll(allowedCategories))
      throw new IllegalArgumentException("Invalid Dot settings");
    if (autoResume)
      throw new IllegalArgumentException("Dot MVP requires manual verification and manual resume");
  }
}
