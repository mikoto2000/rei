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
  @Override
  public String toString() {
    return "RunLlmConnection[profileId=" + profileId + "]";
  }
}
