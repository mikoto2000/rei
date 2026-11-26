package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.modeloptions.ChatModelSelection;
import dev.mikoto2000.rei.modeloptions.ChatPlanAccounts;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionDetails;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** The common connection service consumes account access without implementing OAuth again. */
class ChatPlanAccountBridgeTest {
  private static final class Accounts implements ChatPlanAccounts {
    boolean paused;
    final AtomicInteger registrations = new AtomicInteger();
    final AtomicInteger credentials = new AtomicInteger();
    final AtomicInteger catalogs = new AtomicInteger();

    public String registration(String account) {
      registrations.incrementAndGet();
      assertThat(account).isEqualTo("personal");
      return "registration-one";
    }

    public String credential(String account, String registration, String model) {
      credentials.incrementAndGet();
      throw new AssertionError("Metadata must not acquire a credential");
    }

    public List<String> models(String account, String registration) {
      catalogs.incrementAndGet();
      assertThat(account).isEqualTo("personal");
      assertThat(registration).isEqualTo("registration-one");
      return List.of("first-visible", "configured-model");
    }

    @Override
    public boolean requestsPaused(String account, String registration) {
      assertThat(account).isEqualTo("personal");
      assertThat(registration).isEqualTo("registration-one");
      return paused;
    }
  }

  private <T> ObjectProvider<T> provider(Class<T> type, T value) {
    var beans = new StaticListableBeanFactory();
    if (value != null) beans.addBean("value", value);
    return beans.getBeanProvider(type);
  }

  private LlmProperties properties(boolean plan) {
    return new Binder(
            new MapConfigurationPropertySource(
                plan
                    ? Map.of(
                        "rei.llm.features.chat.active",
                        "subscription",
                        "rei.llm.features.chat.endpoints.subscription.provider",
                        "chatgpt-plan",
                        "rei.llm.features.chat.endpoints.subscription.api",
                        "responses",
                        "rei.llm.features.chat.endpoints.subscription.account-ref",
                        "personal",
                        "rei.llm.features.chat.endpoints.subscription.model",
                        "configured-model")
                    : Map.of(
                        "rei.llm.features.chat.active",
                        "local",
                        "rei.llm.features.chat.endpoints.local.base-url",
                        "http://local.invalid/v1",
                        "rei.llm.features.chat.endpoints.local.authentication",
                        "none",
                        "rei.llm.features.chat.endpoints.local.model",
                        "local-model")))
        .bind("rei.llm", LlmProperties.class)
        .get();
  }

  private ChatConnectionService service(LlmProperties properties) {
    var nativeModel = mock(ChatModel.class);
    when(nativeModel.getOptions())
        .thenThrow(new AssertionError("Named profile read ambient settings"));
    var models =
        new LlmModelProvider(
            nativeModel, properties, new AgentEventFactory(Clock.systemUTC()), event -> {});
    return new ChatConnectionService(
        new ChatEndpointCatalog(properties),
        properties,
        models,
        new ModelHolderService("static-helper"),
        provider(OpenAiConnectionDetails.class, null),
        provider(OpenAiCommonProperties.class, new OpenAiCommonProperties()),
        provider(OpenAiChatProperties.class, new OpenAiChatProperties()),
        provider(dev.mikoto2000.rei.llm.capture.CaptureStore.class, null));
  }

  private AnnotationConfigApplicationContext context(LlmProperties properties, Accounts accounts) {
    var context = new AnnotationConfigApplicationContext();
    context.registerBean(ChatPlanAccounts.class, () -> accounts);
    context.registerBean(ChatConnectionService.class, () -> service(properties));
    context.refresh();
    return context;
  }

  @Test
  void observingPlanAuthorizationUsesOnlyTheLocalRegistrationView() {
    var accounts = new Accounts();
    try (var context = context(properties(true), accounts)) {
      var endpoint = context.getBean(ChatConnectionService.class).currentEndpoint();
      assertThat(endpoint.authenticationState()).isEqualTo("authorized");
      assertThat(java.util.Objects.toString(endpoint.unavailableReason(), ""))
          .doesNotContain("registration requires verification");
      assertThat(accounts.registrations).hasValue(1);
      assertThat(accounts.credentials).hasValue(0);
      assertThat(accounts.catalogs).hasValue(0);
    }
  }

  @Test
  void modelDiscoveryUsesTheSelectedAccountCatalogWithoutNativeInferenceOptions() {
    var accounts = new Accounts();
    try (var context = context(properties(true), accounts)) {
      var selected = context.getBean(ChatConnectionService.class);
      var result = new AtomicReference<ChatModelSelection.Models>();
      assertThatCode(() -> result.set(selected.models())).doesNotThrowAnyException();
      assertThat(result.get().selection())
          .isEqualTo(new ChatModelSelection.Selection("subscription", "configured-model"));
      assertThat(result.get().models()).containsExactly("first-visible", "configured-model");
      assertThat(accounts.catalogs).hasValue(1);
      assertThat(accounts.credentials).hasValue(0);
    }
  }

  @Test
  void anUnauthenticatedLocalProfileNeverConsultsPlanAccounts() {
    var accounts = new Accounts();
    try (var context = context(properties(false), accounts)) {
      var selected = context.getBean(ChatConnectionService.class);
      assertThat(selected.currentEndpoint().authenticationState()).isEqualTo("not-required");
      assertThat(selected.selection())
          .isEqualTo(new ChatModelSelection.Selection("local", "local-model"));
      assertThat(accounts.registrations).hasValue(0);
      assertThat(accounts.credentials).hasValue(0);
      assertThat(accounts.catalogs).hasValue(0);
    }
  }

  @Test
  void aKnownAppUsagePauseIsVisibleWithoutLoggingOutOrAcquiringCredentials() {
    var accounts = new Accounts();
    accounts.paused = true;
    try (var context = context(properties(true), accounts)) {
      var endpoint = context.getBean(ChatConnectionService.class).currentEndpoint();
      assertThat(endpoint.authenticationState()).isEqualTo("authorized");
      assertThat(endpoint.unavailableReason()).contains("ChatGPT app requests paused");
      assertThat(accounts.registrations).hasValue(1);
      assertThat(accounts.credentials).hasValue(0);
      assertThat(accounts.catalogs).hasValue(0);
    }
  }
}
