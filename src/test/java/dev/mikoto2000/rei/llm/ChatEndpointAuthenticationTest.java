package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatEndpointAuthenticationTest {
  private static final String PATH = "rei.llm.features.chat.endpoints.target.";

  @Test
  void authenticationNoneIsExplicitAndDoesNotInheritAKeyFromFlatConfiguration() {
    var settings = settings("openai-compatible", "none");
    settings.put("rei.llm.features.chat.api-key", "PRIVATE_GLOBAL_KEY");
    var catalog = catalog(settings);
    assertThat(view(catalog).at("/authentication").asText()).isEqualTo("none");
    assertThat(catalog.connection("target").getApiKey()).isNull();
    assertThat(view(catalog).toString()).doesNotContain("PRIVATE_GLOBAL_KEY");
  }

  @Test
  void namedEndpointWithoutAuthenticationDoesNotGainLegacyDefaultCredentials() {
    var settings = settings("openai-compatible", null);
    assertThat(view(catalog(settings)).at("/authentication").asText()).isEqualTo("unconfigured");
    settings.put(PATH + "api-key", "PRIVATE_ENDPOINT_KEY");
    assertThat(view(catalog(settings)).at("/authentication").asText()).isEqualTo("api-key");
    assertThat(view(catalog(settings)).toString()).doesNotContain("PRIVATE_ENDPOINT_KEY");
  }

  @Test
  void legacyFlatConfigurationRetainsItsSeparateCompatibilityAuthentication() {
    var catalog = new ChatEndpointCatalog(new LlmProperties());
    assertThat(view(catalog).at("/authentication").asText()).isEqualTo("legacy");
  }

  @Test
  void openAiContractIsExplicitAndNeverGuessedFromHost() {
    var settings = settings("openai-compatible", "api-key");
    settings.put(PATH + "base-url", "https://api.openai.com");
    settings.put(PATH + "api-key", "PRIVATE_ENDPOINT_KEY");
    assertThat(view(catalog(settings)).at("/provider").asText()).isEqualTo("openai-compatible");
    settings.put(PATH + "provider", "openai-api");
    assertThat(view(catalog(settings)).at("/provider").asText()).isEqualTo("openai-api");
  }

  @Test
  void planResolvesOAuthWithoutRequiringLoginDuringConfiguration() {
    var settings = settings("chatgpt-plan", null);
    settings.remove(PATH + "base-url");
    settings.put(PATH + "api", "responses");
    settings.put(PATH + "account-ref", "personal");
    assertThat(view(catalog(settings)).at("/authentication").asText()).isEqualTo("chatgpt-oauth");
  }

  @ParameterizedTest
  @CsvSource({
    "openai-compatible,chatgpt-oauth,base-url,http://localhost:8000,authentication",
    "openai-compatible,api-key,base-url,http://localhost:8000,api-key",
    "openai-compatible,none,api-key,PRIVATE_KEY,api-key",
    "openai-compatible,PRIVATE_CANARY,base-url,http://localhost:8000,authentication",
    "openai-compatible,legacy,base-url,http://localhost:8000,authentication",
    "openai-compatible,unconfigured,base-url,http://localhost:8000,authentication",
    "openai-api,none,base-url,https://api.openai.com,authentication",
    "openai-api,api-key,base-url,http://api.openai.com,base-url",
    "openai-api,api-key,base-url,https://local.invalid,base-url",
    "openai-api,api-key,base-url,https://api.openai.com:8443,base-url",
    "openai-api,api-key,base-url,https://api.openai.com/other,base-url",
    "chatgpt-plan,none,api,responses,authentication",
    "chatgpt-plan,api-key,api,responses,authentication"
  })
  void rejectsAuthenticationAndDestinationMismatchBeforeInference(
      String provider, String authentication, String changed, String value, String field) {
    var settings = settings(provider, authentication);
    if (provider.equals("openai-api")) settings.put(PATH + "api-key", "PRIVATE_ENDPOINT_KEY");
    if (provider.equals("chatgpt-plan")) {
      settings.remove(PATH + "base-url");
      settings.put(PATH + "account-ref", "personal");
    }
    settings.put(PATH + changed, value);
    assertThatThrownBy(() -> catalog(settings))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(PATH + field)
        .hasMessageNotContaining("PRIVATE");
  }

  private HashMap<String, String> settings(String provider, String authentication) {
    var settings = new HashMap<String, String>();
    settings.put("rei.llm.features.chat.active", "target");
    settings.put(PATH + "provider", provider);
    settings.put(PATH + "model", "fixture-model");
    settings.put(PATH + "base-url", "http://localhost:8000");
    if (authentication != null) settings.put(PATH + "authentication", authentication);
    return settings;
  }

  private ChatEndpointCatalog catalog(Map<String, String> settings) {
    return new ChatEndpointCatalog(new Binder(new MapConfigurationPropertySource(settings))
        .bind("rei.llm", LlmProperties.class).get());
  }

  private JsonNode view(ChatEndpointCatalog catalog) {
    return new ObjectMapper().valueToTree(catalog.profiles().getFirst());
  }
}
