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
    props.getFeatures().put("secretary-reply", server);
    var provider = new LlmModelProvider(null, props);
    String fingerprint = provider.privateSecretaryDescriptor().fingerprint();
    var first = provider.privateSecretaryModel(fingerprint);
    org.junit.jupiter.api.Assertions.assertSame(first, provider.privateSecretaryModel(fingerprint));
    server.setModel("changed");
    assertThrows(
        IllegalStateException.class,
        () -> provider.privateSecretaryModel(provider.privateSecretaryDescriptor().fingerprint()));
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
    properties.getFeatures().put("secretary-reply", server);
    var provider = new LlmModelProvider(null, properties);
    server.setBaseUrl("https://user:secret@example.org/v1");
    server.setModel("private");
    assertNull(provider.privateSecretaryDescriptor());
    server.setBaseUrl("http://remote.example.org/v1");
    assertNull(provider.privateSecretaryDescriptor());
    server.setBaseUrl("http://127.0.0.1:1234/v1");
    var first = provider.privateSecretaryDescriptor();
    assertNotNull(first);
    assertFalse(first.fingerprint().contains("private"));
    server.setModel("another");
    assertNotEquals(first.fingerprint(), provider.privateSecretaryDescriptor().fingerprint());
    assertThrows(
        IllegalStateException.class, () -> provider.privateSecretaryModel(first.fingerprint()));
  }
}
