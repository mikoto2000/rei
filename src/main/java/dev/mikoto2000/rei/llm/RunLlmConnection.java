package dev.mikoto2000.rei.llm;

import java.util.Map;

/** Credential-free connection accepted by one Run; never a credential container. */
public record RunLlmConnection(
    String profileId,
    boolean named,
    LlmProvider provider,
    LlmAuthentication authentication,
    String accountRef,
    String accountRegistration,
    ResolvedLlmEndpoint endpoint,
    Map<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support> capabilities,
    Map<String, Object> generation) {
  public RunLlmConnection {
    java.util.Objects.requireNonNull(profileId, "profileId");
    java.util.Objects.requireNonNull(provider, "provider");
    java.util.Objects.requireNonNull(authentication, "authentication");
    java.util.Objects.requireNonNull(endpoint, "endpoint");
    capabilities = Map.copyOf(capabilities);
    generation = RunGenerationSettings.copy(generation);
  }

  @Override
  public Map<LlmEndpointCapabilities.Capability, LlmEndpointCapabilities.Support> capabilities() {
    return Map.copyOf(capabilities);
  }

  @Override
  public Map<String, Object> generation() {
    return generation;
  }

  @Override
  public String toString() {
    return "RunLlmConnection[profileId=" + profileId + "]";
  }

  public String fingerprint() {
    return "";
  }
}
