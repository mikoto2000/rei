package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import picocli.CommandLine;

/**
 * A manual usage acknowledgement only resumes local admission, never authentication or inference.
 */
class ChatGptResumeCommandTest {
  @TempDir Path root;
  private final AtomicInteger http = new AtomicInteger();
  private final StringWriter output = new StringWriter();
  private PlanClient accounts;
  private TokenSet saved;

  private <T> ObjectProvider<T> provider(Class<T> type, T value) {
    var beans = new StaticListableBeanFactory();
    if (value != null) beans.addBean("fixture", value);
    return beans.getBeanProvider(type);
  }

  private CommandLine command() {
    var store = new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION);
    saved = ProtectedCredentialStoreTest.tokens("access-secret", "refresh-secret");
    store.locked(tx -> {
      tx.save("personal", saved);
      return null;
    });
    PlanTransport transport =
        (uri, method, headers, body, timeout, cancelled) -> {
          http.incrementAndGet();
          throw new AssertionError("Local usage acknowledgement must not dispatch any HTTP");
        };
    var oauth = new PlanOAuth(store, transport, (id, app, nonce) -> null, Clock.systemUTC());
    accounts = new PlanClient(oauth, transport, Clock.systemUTC());
    accounts.pauseRequests("personal", saved.registrationId());
    var command =
        new ChatGptCommand(
            provider(PlanOAuth.class, oauth),
            provider(PlanClient.class, accounts),
            provider(PlanLogin.class, null));
    var line = new CommandLine(command);
    line.setOut(new PrintWriter(output, true));
    line.setErr(new PrintWriter(output, true));
    return line;
  }

  @Test
  void explicitUsageAcknowledgementResumesOnlyTheChosenRegistrationWithoutHttp() {
    var line = command();
    assertThat(line.execute("resume", "--account", "personal", "--confirm-usage")).isZero();
    assertThat(accounts.requestsPaused("personal", saved.registrationId())).isFalse();
    assertThat(http).hasValue(0);
    TokenSet current =
        new ProtectedCredentialStore(root, ProtectedCredentialStoreTest.TEST_PROTECTION)
            .locked(tx -> tx.load("personal"));
    assertThat(current).isEqualTo(saved);
    assertThat(output.toString())
        .contains("No inference sent", "https://chatgpt.com/settings/usage")
        .doesNotContain("access-secret", "refresh-secret", saved.registrationId());
  }

  @Test
  void accountSelectionAloneDoesNotAcknowledgeUsage() {
    var line = command();
    assertThat(line.execute("resume", "--account", "personal")).isNotZero();
    assertThat(accounts.requestsPaused("personal", saved.registrationId())).isTrue();
    assertThat(http).hasValue(0);
  }

  @Test
  void anExplicitFalseAcknowledgementCannotResumeRequests() {
    var line = command();
    assertThat(line.execute("resume", "--account", "personal", "--confirm-usage=false"))
        .isNotZero();
    assertThat(accounts.requestsPaused("personal", saved.registrationId())).isTrue();
    assertThat(http).hasValue(0);
  }
}
