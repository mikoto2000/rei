package dev.mikoto2000.rei.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SecretaryPrivateModelTest {
  @Test
  void privateModelClientIsReusedAndEndpointChangesRequireRestart() {
    var props = new LlmProperties();
    var server = new LlmProperties.Server();
    server.setBaseUrl("http://127.0.0.1:1234/v1");
    server.setModel("local-private");
    props.setFeature("secretary-reply", server);
    var provider = new LlmModelProvider(null, props);
    String fingerprint = provider.privateSecretaryDescriptor().fingerprint();
    var first = provider.privateSecretaryModel(fingerprint);
    org.junit.jupiter.api.Assertions.assertSame(first, provider.privateSecretaryModel(fingerprint));
    server.setModel("changed");
    props.setFeature("secretary-reply", server);
    assertThrows(
        IllegalStateException.class,
        () -> provider.privateSecretaryModel(provider.privateSecretaryDescriptor().fingerprint()));
  }

  @Test
  void privateModelUsesTheSameSettingsForFingerprintAndConstruction() {
    var properties = org.mockito.Mockito.mock(LlmProperties.class);
    var validated = new LlmProperties.Server();
    validated.setBaseUrl("https://private.invalid/v1");
    validated.setModel("private-model");
    validated.setMaxOutputTokens(128);
    var changed = new LlmProperties.Server();
    changed.setBaseUrl("https://changed.invalid/v1");
    changed.setModel("changed-model");
    changed.setMaxOutputTokens(128);
    org.mockito.Mockito.when(properties.feature("secretary-reply"))
        .thenReturn(validated, validated, changed);
    var provider = new LlmModelProvider(null, properties);
    String fingerprint = provider.privateSecretaryDescriptor().fingerprint();

    var model = provider.privateSecretaryModel(fingerprint);

    org.junit.jupiter.api.Assertions.assertEquals("private-model", model.getOptions().getModel());
    org.mockito.Mockito.verify(properties, org.mockito.Mockito.times(2)).feature("secretary-reply");
  }

  @Test
  void privateReplyNeverFallsBackToTheChatModel() {
    var provider = new LlmModelProvider(null, new LlmProperties());
    assertNull(provider.privateSecretaryDescriptor());
    assertThrows(IllegalStateException.class, () -> provider.privateSecretaryModel("anything"));
  }

  @Test
  void descriptorRejectsCredentialsInUrlAndBindsTheExactModel() {
    var properties = new LlmProperties();
    var server = new LlmProperties.Server();
    properties.setFeature("secretary-reply", server);
    var provider = new LlmModelProvider(null, properties);
    server.setBaseUrl("https://user:secret@example.org/v1");
    server.setModel("private");
    properties.setFeature("secretary-reply", server);
    assertNull(provider.privateSecretaryDescriptor());
    server.setBaseUrl("http://remote.example.org/v1");
    properties.setFeature("secretary-reply", server);
    assertNull(provider.privateSecretaryDescriptor());
    server.setBaseUrl("http://127.0.0.1:1234/v1");
    properties.setFeature("secretary-reply", server);
    var first = provider.privateSecretaryDescriptor();
    assertNotNull(first);
    assertFalse(first.fingerprint().contains("private"));
    server.setModel("another");
    properties.setFeature("secretary-reply", server);
    assertNotEquals(first.fingerprint(), provider.privateSecretaryDescriptor().fingerprint());
    assertThrows(
        IllegalStateException.class, () -> provider.privateSecretaryModel(first.fingerprint()));
  }
}
