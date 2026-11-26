package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.modeloptions.ChatPlanAccounts;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** The application setting controls per-registration inference capacity, not whole delegations. */
class PlanRequestConcurrencySettingsTest {
  @TempDir Path root;

  private PlanProperties settings(String maximum) {
    var values = new HashMap<String, Object>();
    values.put("rei.chatgpt-plan.enabled", "true");
    values.put("rei.chatgpt-plan.credential-directory", root.resolve("credentials").toString());
    if (maximum != null) values.put("rei.chatgpt-plan.max-concurrent", maximum);
    return new Binder(new MapConfigurationPropertySource(values))
        .bind("rei.chatgpt-plan", PlanProperties.class)
        .get();
  }

  @Test
  void omittedRequestConcurrencyIsConservativelyOne() {
    assertThat(new ObjectMapper().valueToTree(settings(null)).path("maxConcurrent").asInt(-1))
        .isEqualTo(1);
  }

  @Test
  void requestConcurrencyCanBeExplicitlyConfigured() {
    assertThat(new ObjectMapper().valueToTree(settings("2")).path("maxConcurrent").asInt(-1))
        .isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "9"})
  void invalidRequestConcurrencyIsRejectedInsteadOfIgnored(String maximum) {
    assertThrows(BindException.class, () -> settings(maximum));
  }

  private ChatPlanAccounts.Request begin(PlanClient client, String registration, String model) {
    var result = new AtomicReference<ChatPlanAccounts.Request>();
    assertThatCode(() -> result.set(client.begin("personal", registration, model)))
        .doesNotThrowAnyException();
    return result.get();
  }

  @Test
  void theActualPlanConfigurationAppliesTheConfiguredCapacityToItsSharedClient() {
    var store = new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
    var saved = ProtectedCredentialStoreTest.tokens("access", "refresh");
    store.locked(s -> {
      s.save("personal", saved);
      return null;
    });
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          throw new AssertionError("Capacity configuration must not dispatch HTTP");
        };
    var oauth = new PlanOAuth(store, transport, (id, client, nonce) -> null, Clock.systemUTC());
    try (var context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "plan-test",
                  Map.of(
                      "rei.chatgpt-plan.enabled",
                      "true",
                      "rei.chatgpt-plan.max-concurrent",
                      "2",
                      "rei.chatgpt-plan.credential-directory",
                      root.resolve("credentials").toString(),
                      "rei.chatgpt-plan.app-server.enabled",
                      "false")));
      context.registerBean("clock", Clock.class, Clock::systemUTC);
      context.register(PlanConfiguration.class);
      context.addBeanFactoryPostProcessor(beans -> {
        // Replace owned boundaries before singleton creation; no DPAPI, JWKS or network is used.
        beans.registerSingleton("planTransport", transport);
        beans.registerSingleton("planCredentialStore", store);
        beans.registerSingleton("planOAuth", oauth);
      });
      context.refresh();
      assertThat(context.getBean(PlanOAuth.class)).isSameAs(oauth);
      var client = context.getBean(PlanClient.class);
      try (var first = begin(client, saved.registrationId(), "model-a");
          var second = begin(client, saved.registrationId(), "model-b")) {
        assertThat(first.invalidated().toCompletableFuture()).isNotDone();
        assertThat(second.invalidated().toCompletableFuture()).isNotDone();
        assertEquals(
            ChatGptException.Code.BUSY,
            assertThrows(
                    ChatGptException.class,
                    () -> client.begin("personal", saved.registrationId(), "model-c"))
                .code());
      }
    }
  }
}
