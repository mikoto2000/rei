package dev.mikoto2000.rei.timing;

import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Run-local secret key and bounded metadata only. Never exposes input text or a reusable plain
 * hash.
 */
public final class PrefixDiagnostics {
  public record Source(String id, String content) {}

  public record Region(String id, int order, String hash, long length, Boolean changed) {}

  public record Snapshot(List<Region> regions, String firstChanged, boolean truncated) {
    public Snapshot {
      regions = List.copyOf(regions);
    }
  }

  private final byte[] key = new byte[32];
  private List<Region> previous = List.of();

  public PrefixDiagnostics() {
    new java.security.SecureRandom().nextBytes(key);
  }

  public synchronized Snapshot observe(List<Source> sources) {
    var regions = new ArrayList<Region>();
    String first = null;
    int size = Math.min(64, sources.size());
    for (int i = 0; i < size; i++) {
      var source = sources.get(i);
      String content = source.content() == null ? "" : source.content();
      String hash = hash(content);
      var old = i < previous.size() ? previous.get(i) : null;
      Boolean changed =
          previous.isEmpty()
              ? null
              : old == null || !old.id().equals(source.id()) || !old.hash().equals(hash);
      if (Boolean.TRUE.equals(changed) && first == null) first = source.id();
      regions.add(new Region(source.id(), i, hash, content.length(), changed));
    }
    if (first == null && previous.size() > size) first = previous.get(size).id();
    previous = List.copyOf(regions);
    return new Snapshot(regions, first, sources.size() > 64);
  }

  private String hash(String content) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      // Bound temporary encoding allocations even for a large tool result.
      for (int at = 0; at < content.length(); ) {
        int end = Math.min(content.length(), at + 4096);
        if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) end--;
        mac.update(content.substring(at, end).getBytes(StandardCharsets.UTF_8));
        at = end;
      }
      return HexFormat.of().formatHex(mac.doFinal());
    } catch (java.security.GeneralSecurityException error) {
      throw new IllegalStateException("Prefix diagnostic unavailable");
    }
  }

  public static List<Source> sources(org.springframework.ai.chat.prompt.Prompt prompt) {
    var sources = new ArrayList<Source>();
    if (prompt.getOptions()
            instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
        && options.getToolCallbacks() != null) {
      // Logical API field, not a claim about the provider's token serialization order.
      var definitions = new StringBuilder();
      for (var callback : options.getToolCallbacks()) {
        var d = callback.getToolDefinition();
        definitions
            .append(d.name())
            .append('\0')
            .append(d.description())
            .append('\0')
            .append(d.inputSchema())
            .append('\0');
      }
      sources.add(new Source("TOOL_DEFINITIONS", definitions.toString()));
    }
    for (var message : prompt.getInstructions()) {
      if (sources.size() >= 65) break;
      String id = message.getMessageType().name();
      if (Boolean.TRUE.equals(message.getMetadata().get("rei.workingSet"))) id = "WORKING_SET";
      else if (message.getText() != null && message.getText().startsWith("Run observations ("))
        id = "RUN_OBSERVATIONS";
      else if (message.getText() != null && message.getText().startsWith("Conversation Summary ("))
        id = "CONTEXT_SUMMARY";
      String text = message.getText();
      if (message instanceof org.springframework.ai.chat.messages.AssistantMessage assistant)
        text = String.valueOf(text) + assistant.getToolCalls();
      if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage tools)
        text = tools.getResponses().toString();
      sources.add(new Source(id, text));
    }
    return sources;
  }
}
