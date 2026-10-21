package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.artifact.ArtifactConfiguration;
import dev.mikoto2000.rei.artifact.ArtifactStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.sqlite.SQLiteDataSource;

/** 成果物HTTP通信外部連携の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ArtifactHttpIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration(proxyBeanMethods = false)
  @Import({WebApiIntegrationTest.Config.class, ArtifactConfiguration.class})
  static class Config {
    /** {@code data} の条件を再現するための補助情報を返す。 */
    @Bean("memoryConsolidationDataSource")
    javax.sql.DataSource data(
        @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
      var data = new SQLiteDataSource();
      data.setUrl("jdbc:sqlite:" + Path.of(directory).resolve("state.db"));
      return data;
    }
  }

  /** Web APIによる操作で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void defaultOffBearerAndRestartDeliverTheSameVerifiedSnapshot() throws Exception {
    String project, id;
    try (var client = HttpClient.newHttpClient()) {
      try (var context = application().run(args(false))) {
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        assertThat(send(client, port, "/api/v1/artifacts", true).statusCode()).isEqualTo(404);
      }
      try (var context = application().run(args(true))) {
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        project = context.getBean(ProjectRegistry.class).resolve(root).id();
        var item =
            context
                .getBean(ArtifactStore.class)
                .publish(
                    new AgentRunContext("run", "session", root, project),
                    "document",
                    "text/plain",
                    "result.txt",
                    "safe".getBytes());
        id = item.artifactId();
        assertThat(send(client, port, "/api/v1/artifacts", false).statusCode()).isEqualTo(401);
        var bytes =
            send(
                client,
                port,
                "/api/v1/artifacts/" + id + "/content?projectId=" + project + "&sessionId=session",
                true);
        assertThat(bytes.statusCode()).isEqualTo(200);
        assertThat(bytes.body()).isEqualTo("safe");
        assertThat(bytes.headers().firstValue("Content-Disposition").orElseThrow())
            .startsWith("attachment;");
        assertThat(
                send(
                        client,
                        port,
                        "/api/v1/artifacts/" + id + "?projectId=" + project + "&sessionId=other",
                        true)
                    .statusCode())
            .isEqualTo(404);
      }
      try (var context = application().run(args(true))) {
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        assertThat(
                send(
                        client,
                        port,
                        "/api/v1/artifacts/"
                            + id
                            + "/content?projectId="
                            + project
                            + "&sessionId=session",
                        true)
                    .body())
            .isEqualTo("safe");
        assertThat(
                send(
                        client,
                        port,
                        "/api/v1/artifacts?projectId=" + project + "&sessionId=session&runId=run",
                        true)
                    .body())
            .contains(id);
      }
    }
  }

  /** {@code application} の条件を再現するための補助情報を返す。 */
  private SpringApplication application() {
    var app = new SpringApplication(Config.class);
    WebApplication.configure(app, "integration-key");
    return app;
  }

  /** {@code args} の条件を再現するための補助情報を返す。 */
  private String[] args(boolean enabled) {
    return new String[] {
      "--rei.web.port=0",
      "--rei.data-dir=" + root,
      "--rei.artifacts.enabled=" + enabled,
      "--logging.config=classpath:web-test-logback.xml"
    };
  }

  /** {@code send} の条件を再現するための補助情報を返す。 */
  private HttpResponse<String> send(HttpClient client, int port, String path, boolean auth)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5));
    if (auth) request.header("Authorization", "Bearer integration-key");
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
