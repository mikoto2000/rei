package dev.mikoto2000.rei.core.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.core")
public record CoreProperties(
    String systemPrompt
) {}
