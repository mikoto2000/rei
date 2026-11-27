package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Full application bootstrap in an owned JVM with empty data and no inherited service credentials.
 */
@Tag("integration")
class NamedConnectionStartupTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"local", "subscription"})
  void applicationStartsWithoutAnOpenAiKeyOrPlanLogin(String active) throws Exception {
    var work = Files.createDirectory(directory.resolve(active));
    var arguments = work.resolve("startup.args");
    var log = work.resolve("startup.log");
    var values =
        List.of(
            "--enable-native-access=ALL-UNNAMED",
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            Worker.class.getName(),
            work.toString(),
            active);
    Files.write(
        arguments,
        values.stream()
            .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .toList());
    var builder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
            .directory(work.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    builder
        .environment()
        .keySet()
        .removeIf(name -> {
          String upper = name.toUpperCase(Locale.ROOT);
          return upper.startsWith("REI_")
              || upper.startsWith("OPENAI_")
              || upper.startsWith("SPRING_")
              || upper.equals("JAVA_TOOL_OPTIONS")
              || upper.equals("JDK_JAVA_OPTIONS")
              || upper.equals("_JAVA_OPTIONS");
        });
    builder.environment().put("REI_DATA_DIR", work.resolve("data").toString());
    var child = builder.start();
    boolean completed = child.waitFor(120, TimeUnit.SECONDS);
    if (!completed) {
      child.destroyForcibly();
      child.waitFor(5, TimeUnit.SECONDS);
    }
    String diagnostics =
        new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8);
    var applicationLog = work.resolve("data/logs/rei.log");
    if (Files.exists(applicationLog))
      diagnostics +=
          "\n"
              + new String(
                  Files.readAllBytes(applicationLog), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(completed).as("owned startup JVM completed: %s", diagnostics).isTrue();
    assertThat(child.exitValue()).as("startup: %s", diagnostics).isZero();
    assertThat(work.resolve("verified.txt"))
        .as("startup reached assertions: %s", diagnostics)
        .exists();
    assertThat(Files.readString(work.resolve("verified.txt"))).isEqualTo(active);
  }

  public static class Worker {
    public static void main(String[] arguments) throws Exception {
      Path work = Path.of(arguments[0]);
      String active = arguments[1];
      Path data = work.resolve("data");
      assertThat(System.getenv("REI_DATA_DIR")).isEqualTo(data.toString());
      assertThat(System.getenv("OPENAI_API_KEY")).isNull();
      assertThat(System.getenv("REI_OPENAI_API_KEY")).isNull();
      System.setProperty("rei.data-dir", data.toString());
      System.setProperty("spring.devtools.restart.enabled", "false");
      var requests = new java.util.concurrent.atomic.AtomicInteger();
      var server =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
          });
      server.start();
      String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
      var application =
          new org.springframework.boot.SpringApplication(dev.mikoto2000.rei.ReiApplication.class);
      try (var context =
          application.run(
              "--spring.config.location=classpath:/application.yaml",
              "--spring.main.web-application-type=none",
              "--rei.web.enabled=false",
              "--spring.ai.openai.api-key=",
              "--spring.ai.openai.base-url=" + base,
              "--rei.embedding.enabled=false",
              "--rei.skills.enabled=false",
              "--rei.google.token-refresh.enabled=false",
              "--rei.chatgpt-plan.enabled=true",
              "--rei.llm.features.chat.active=" + active,
              "--rei.llm.features.chat.endpoints.local.provider=openai-compatible",
              "--rei.llm.features.chat.endpoints.local.api=responses",
              "--rei.llm.features.chat.endpoints.local.authentication=none",
              "--rei.llm.features.chat.endpoints.local.base-url=" + base,
              "--rei.llm.features.chat.endpoints.local.model=fixture-local-model",
              "--rei.llm.features.chat.endpoints.subscription.provider=chatgpt-plan",
              "--rei.llm.features.chat.endpoints.subscription.api=responses",
              "--rei.llm.features.chat.endpoints.subscription.authentication=chatgpt-oauth",
              "--rei.llm.features.chat.endpoints.subscription.account-ref=personal",
              "--rei.llm.features.chat.endpoints.subscription.model=fixture-plan-model")) {
        assertThat(context.getEnvironment().getProperty("spring.ai.openai.api-key")).isEmpty();
        var catalog = context.getBean(ChatEndpointCatalog.class);
        assertThat(catalog.profiles())
            .extracting(ChatEndpointCatalog.Profile::id)
            .containsExactlyInAnyOrder("local", "subscription");
        assertThat(catalog.connection("local").getApiKey()).isNull();
        var service = context.getBean(ChatConnectionService.class);
        assertThat(service.selection().endpoint()).isEqualTo(active);
        assertThat(service.endpoints()).hasSize(2);
        assertThat(requests.get()).isZero();
        Files.writeString(work.resolve("verified.txt"), active);
      } finally {
        server.stop(0);
      }
    }
  }
}
