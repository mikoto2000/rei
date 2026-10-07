package dev.mikoto2000.rei.web;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.application.run.RunRegistry;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
class DailyPlanningHttpIntegrationTest {
  @TempDir Path root;
  @Configuration(proxyBeanMethods=false)
  @Import({WebApiIntegrationTest.Config.class,dev.mikoto2000.rei.planning.DailyPlanningConfiguration.class})
  static class Config {
    @Bean("memoryConsolidationDataSource") javax.sql.DataSource data(@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory){return new DriverManagerDataSource("jdbc:sqlite:"+Path.of(directory).resolve("state.db"));}
  }
  @Test void defaultOffBearerAllowlistAndRestartReadOnlyProjection() throws Exception {
    var registry=new ProjectRegistry(root.resolve("projects.json"));var project=registry.resolve(root);
    var foreign=registry.resolve(Files.createDirectory(root.resolve("foreign")));
    try(var client=HttpClient.newHttpClient()) {
      try(var context=app().run(args(false,project.id()))) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        assertThat(get(client,port,"",true).statusCode()).isEqualTo(404);
      }
      try(var context=app().run(args(true,project.id()))) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        context.getBean(RunRegistry.class).register(new AgentRunContext("owned","session",root,project.id()));
        context.getBean(RunRegistry.class).register(new AgentRunContext("foreign","other",foreign.root(),foreign.id()));
        assertThat(get(client,port,"",false).statusCode()).isEqualTo(401);
        var report=get(client,port,"",true);assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.body()).contains("DETERMINISTIC","run:owned","Today").doesNotContain("run:foreign",root.toString());
        assertThat(get(client,port,"?projectId="+foreign.id(),true).statusCode()).isEqualTo(404);
      }
      try(var context=app().run(args(true,project.id()))) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        assertThat(get(client,port,"",true).body()).contains("run:owned","UNKNOWN").doesNotContain("run:foreign");
      }
    }
  }
  private SpringApplication app(){var app=new SpringApplication(Config.class);WebApplication.configure(app,"integration-key");return app;}
  private String[] args(boolean enabled,String project){return new String[]{"--rei.web.port=0","--rei.data-dir="+root,"--rei.today.enabled="+enabled,"--rei.today.projects="+project,"--logging.config=classpath:web-test-logback.xml"};}
  private static HttpResponse<String> get(HttpClient client,int port,String query,boolean bearer)throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/today"+query)).timeout(java.time.Duration.ofSeconds(10));
    if(bearer)request.header("Authorization","Bearer integration-key");return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
  }
}
