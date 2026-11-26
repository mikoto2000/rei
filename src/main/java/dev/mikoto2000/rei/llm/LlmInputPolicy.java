package dev.mikoto2000.rei.llm;

import java.util.Set;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;

/** Named normal-chat input admission; no upload, fetching or provider-specific conversion. */
final class LlmInputPolicy {
  private static final Set<String> IMAGES =
      Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
  private static final Set<String> FILES = Set.of("application/pdf", "text/plain");
  private static final int MAX_BYTES = 4_194_304;

  private LlmInputPolicy() {}

  static void requireMedia(Prompt prompt, RunLlmConnection connection) {
    for (var message : prompt.getInstructions()) {
      if (!(message instanceof UserMessage user)) continue;
      for (var media : user.getMedia()) {
        String mime = media.getMimeType().toString().toLowerCase(java.util.Locale.ROOT);
        var capability =
            IMAGES.contains(mime)
                ? LlmEndpointCapabilities.Capability.VISION
                : FILES.contains(mime) ? LlmEndpointCapabilities.Capability.FILE_INPUT : null;
        if (capability == null)
          throw new IllegalArgumentException("Unsupported normal chat media type");
        if (connection == null
            || !connection.named()
            || connection.capabilities().get(capability)
                != LlmEndpointCapabilities.Support.SUPPORTED)
          throw new IllegalArgumentException(
              "Input capability is unsupported or unconfirmed: " + capability);
        requireData(media, mime, capability, connection.endpoint().apiType());
      }
    }
  }

  private static void requireData(
      Media media, String mime, LlmEndpointCapabilities.Capability capability, LlmApiType api) {
    if (capability == LlmEndpointCapabilities.Capability.FILE_INPUT) {
      String name = media.getName();
      if (name == null
          || name.isBlank()
          || name.length() > 1024
          || name.indexOf('/') >= 0
          || name.indexOf('\\') >= 0
          || name.codePoints().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("FILE_INPUT requires a bounded file name");
    }
    var data = media.getData();
    if (data instanceof byte[] bytes) {
      if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalidData();
      return;
    }
    if (!(data instanceof String text) || text.length() > 6_000_000) throw invalidData();
    if (text.startsWith("data:")) {
      String prefix = "data:" + mime + ";base64,";
      if (!text.startsWith(prefix)) throw invalidData();
      try {
        var bytes = java.util.Base64.getDecoder().decode(text.substring(prefix.length()));
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalidData();
      } catch (IllegalArgumentException invalid) {
        throw invalidData();
      }
      return;
    }
    if (capability == LlmEndpointCapabilities.Capability.FILE_INPUT
        && api == LlmApiType.CHAT_COMPLETIONS)
      throw new IllegalArgumentException("Chat Completions FILE_INPUT requires inline data");
    try {
      var uri = java.net.URI.create(text);
      if (!Set.of("http", "https").contains(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || uri.getPort() < -1
          || uri.getPort() == 0
          || uri.getPort() > 65535) throw invalidData();
    } catch (IllegalArgumentException invalid) {
      throw invalidData();
    }
  }

  private static IllegalArgumentException invalidData() {
    return new IllegalArgumentException("Unsupported, invalid or oversized normal chat media data");
  }
}
