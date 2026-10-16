package dev.mikoto2000.rei.dot;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
