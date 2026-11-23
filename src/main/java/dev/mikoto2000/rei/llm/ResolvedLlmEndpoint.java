package dev.mikoto2000.rei.llm;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable effective configuration. Credentials live behind a server-side reference. */
public record ResolvedLlmEndpoint(
    String endpointId,
    long generation,
    LlmApiType apiType,
    String baseUrl,
    String credentialRef,
    String model,
    LlmCapabilities capabilities,
    Integer maxOutputTokens,
    Double temperature,
    Duration readTimeout) {
  public ResolvedLlmEndpoint {
    Objects.requireNonNull(endpointId, "endpointId");
    Objects.requireNonNull(apiType, "apiType");
    Objects.requireNonNull(credentialRef, "credentialRef");
    Objects.requireNonNull(capabilities, "capabilities");
    Objects.requireNonNull(readTimeout, "readTimeout");
    if (generation < 1 || (maxOutputTokens != null && maxOutputTokens < 1))
      throw new IllegalArgumentException("Generation and explicit output limit must be positive");
  }

  @Override
  public String toString() {
    return "ResolvedLlmEndpoint[endpointId="
        + endpointId
        + ", generation="
        + generation
        + ", apiType="
        + apiType
        + "]";
  }

  /** Replay compatibility identity. Key rotation deliberately does not change this identity. */
  public String fingerprint() {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (var field :
          List.of(
              endpointId,
              Long.toString(generation),
              apiType.name(),
              Objects.toString(baseUrl, ""),
              Objects.toString(model, ""),
              capabilities.toString())) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
