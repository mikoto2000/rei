package dev.mikoto2000.rei.web;
public record ChatRequest(String message, String projectId, String sessionId,
    dev.mikoto2000.rei.core.chat.AgentRunContext.Mode mode) {
  public ChatRequest(String message, String projectId, String sessionId) { this(message,projectId,sessionId,null); }
}
