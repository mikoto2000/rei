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
    var values = new java.util.TreeMap<String, Object>();
    values.put("profileId", profileId);
    values.put("named", named);
    values.put("provider", provider.name());
    values.put("authentication", authentication.name());
    values.put("accountRef", accountRef);
    values.put("accountRegistration", accountRegistration);
    var resolved = new java.util.TreeMap<String, Object>();
    resolved.put("id", endpoint.endpointId());
    resolved.put("generation", endpoint.generation());
    resolved.put("api", endpoint.apiType().name());
    resolved.put("baseUrl", endpoint.baseUrl());
    resolved.put("credentialRef", endpoint.credentialRef());
    resolved.put("model", endpoint.model());
    resolved.put("replay", endpoint.capabilities().reasoningReplay().name());
    resolved.put("maxTools", endpoint.capabilities().maxTools());
    resolved.put("supportedSettings", endpoint.capabilities().supportedSettings().stream()
        .map(Enum::name).sorted().toList());
    resolved.put("maxOutputTokens", endpoint.maxOutputTokens());
    resolved.put("temperature", endpoint.temperature());
    resolved.put("readTimeout", endpoint.readTimeout().toString());
    values.put("endpoint", resolved);
    var effective = new java.util.TreeMap<String, String>();
    capabilities.forEach((name, support) -> effective.put(name.name(), support.name()));
    values.put("capabilities", effective);
    values.put("generation", generation);
    try {
      var json = new com.fasterxml.jackson.databind.ObjectMapper()
          .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(json.writeValueAsBytes(values)));
    } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException impossible) {
      throw new IllegalStateException("Run connection identity is unavailable");
    }
  }
}
