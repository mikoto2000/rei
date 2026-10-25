package dev.mikoto2000.rei.google;

/** Separate Gmail opt-in; draft/send scopes are never requested for read-only use. */
@org.springframework.boot.context.properties.ConfigurationProperties("rei.google.gmail")
public record GmailProperties(boolean enabled, boolean draftsEnabled, boolean sendEnabled) {}
