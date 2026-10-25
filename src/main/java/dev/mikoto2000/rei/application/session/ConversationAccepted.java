package dev.mikoto2000.rei.application.session;

/** New admitted input only. Consumers must treat text as data and isolate their failures. */
public record ConversationAccepted(String projectId, String sessionId, String runId, String text) {}
