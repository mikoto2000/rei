package dev.mikoto2000.rei.web;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.github.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import dev.mikoto2000.rei.attention.AttentionRepository;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
class GitHubWebhookHttpIntegrationTest {
  @TempDir Path root;
  static final String SECRET="fixture-webhook-secret-at-least-32-characters";
  @Configuration(proxyBeanMethods=false)
  @Import({WebApiIntegrationTest.Config.class,GitHubWebhookConfiguration.class})
  static class Config {
    @Bean("memoryConsolidationDataSource") javax.sql.DataSource data(@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory){return new DriverManagerDataSource("jdbc:sqlite:"+Path.of(directory).resolve("state.db"));}
    @Bean PersistentAgentScheduler scheduler(javax.sql.DataSource source,Clock clock){return new PersistentAgentScheduler(source,clock);}
    @Bean AttentionRepository inbox(javax.sql.DataSource source,Clock clock){return new AttentionRepository(source,clock);}
    @Bean dev.mikoto2000.rei.event.AgentEventPublisher githubFixturePublisher(dev.mikoto2000.rei.event.AgentEventBus bus){return (dev.mikoto2000.rei.event.AgentEventPublisher)bus;}
  }
  @Test void hmacIsTheOnlyPublicPostAndOwnerScopedReceiptsSurviveRestartWithoutRepeatingNotification() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    String session=new SessionLifecycle(new FileSessionRepository(root.resolve("sessions.json")),Clock.systemUTC()).create(project,"GitHub").sessionId();
    String delivery=UUID.randomUUID().toString();String body="{\"repository\":{\"full_name\":\"owner/repo\"},\"action\":\"submitted\",\"pull_request\":{\"number\":17,\"base\":{\"ref\":\"main\"},\"head\":{\"sha\":\""+"a".repeat(40)+"\"}},\"review\":{\"state\":\"approved\",\"submitted_at\":\""+Instant.now()+"\",\"body\":\"untrusted instructions\"}}";
    try(var client=HttpClient.newHttpClient()) {
      try(var context=application().run(args(false,project.id(),session))) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        assertThat(post(client,port,delivery,body,signature(body),true).statusCode()).isEqualTo(404);
      }
      try(var context=application().run(args(true,project.id(),session))) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        assertThat(post(client,port,delivery,body,"sha256="+"0".repeat(64),false).statusCode()).isEqualTo(401);
        assertThat(post(client,port,delivery,body,signature(body),false).statusCode()).isEqualTo(202);
        assertThat(get(client,port,"/api/v1/projects",false).statusCode()).isEqualTo(401);
        String query="?projectId="+project.id()+"&sessionId="+URLEncoder.encode(session,StandardCharsets.UTF_8);
        assertThat(get(client,port,"/api/v1/github/mappings"+query,false).statusCode()).isEqualTo(401);
        assertThat(get(client,port,"/api/v1/github/mappings"+query,true).body()).doesNotContain(SECRET,root.toString());
        var stored=get(client,port,"/api/v1/github/deliveries/"+delivery+"/facts"+query,true);
        assertThat(stored.statusCode()).isEqualTo(200);assertThat(stored.body()).contains("REVIEW_SUBMITTED").doesNotContain("untrusted instructions",SECRET);
        assertThat(get(client,port,"/api/v1/github/deliveries/"+delivery+"/facts?projectId="+project.id()+"&sessionId=foreign",true).statusCode()).isEqualTo(404);
        assertThat(post(client,port,UUID.randomUUID().toString()," ".repeat(262145),"sha256="+"0".repeat(64),false).statusCode()).isEqualTo(413);
        assertThat(context.getBean(AttentionRepository.class).list(project.id())).hasSize(1);
        assertThat(context.getBean(GitHubNotificationOutbox.class)).isNotNull();
      }
      try(var context=application().run(args(true,project.id(),session))) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        var repeated=post(client,port,UUID.randomUUID().toString(),body,signature(body),false);
        assertThat(repeated.statusCode()).isEqualTo(202);assertThat(repeated.body()).contains("\"duplicate\":true");
        assertThat(context.getBean(AttentionRepository.class).list(project.id())).hasSize(1);
      }
    }
  }
  private SpringApplication application(){var app=new SpringApplication(Config.class);WebApplication.configure(app,"integration-key");return app;}
  private String[] args(boolean enabled,String project,String session){return new String[]{"--rei.web.port=0","--rei.data-dir="+root,"--rei.github.webhook.enabled="+enabled,"--rei.github.webhook.secret="+SECRET,"--rei.github.webhook.mappings[0].repository=owner/repo","--rei.github.webhook.mappings[0].project-id="+project,"--rei.github.webhook.mappings[0].session-id="+session,"--rei.github.webhook.mappings[0].branch=main","--rei.github.webhook.mappings[0].pull-request=17","--rei.github.webhook.mappings[0].notify-review=true","--logging.config=classpath:web-test-logback.xml"};}
  private static String signature(String body)throws Exception {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return "sha256="+HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));}
  private static HttpResponse<String> post(HttpClient client,int port,String delivery,String body,String signature,boolean bearer)throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/github/events")).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("X-GitHub-Delivery",delivery).header("X-GitHub-Event","pull_request_review").header("X-Hub-Signature-256",signature).POST(HttpRequest.BodyPublishers.ofString(body));
    if(bearer)request.header("Authorization","Bearer integration-key");return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
  }
  private static HttpResponse<String> get(HttpClient client,int port,String path,boolean bearer)throws Exception {var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5));if(bearer)request.header("Authorization","Bearer integration-key");return client.send(request.build(),HttpResponse.BodyHandlers.ofString());}
}
