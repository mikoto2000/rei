package dev.mikoto2000.rei.web;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.boot.SpringApplication;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.artifact.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
class ArtifactHttpIntegrationTest {
  @TempDir Path root;
  @Configuration(proxyBeanMethods=false)
  @Import({WebApiIntegrationTest.Config.class,ArtifactConfiguration.class})
  static class Config {
    @Bean("memoryConsolidationDataSource") javax.sql.DataSource data(@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
      var data=new SQLiteDataSource();data.setUrl("jdbc:sqlite:"+Path.of(directory).resolve("state.db"));return data;
    }
  }
  @Test void defaultOffBearerAndRestartDeliverTheSameVerifiedSnapshot() throws Exception {
    String project,id;
    try(var client=HttpClient.newHttpClient()) {
      try(var context=application().run(args(false))) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        assertThat(send(client,port,"/api/v1/artifacts",true).statusCode()).isEqualTo(404);
      }
      try(var context=application().run(args(true))) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        project=context.getBean(ProjectRegistry.class).resolve(root).id();
        var item=context.getBean(ArtifactStore.class).publish(new AgentRunContext("run","session",root,project),"document","text/plain","result.txt","safe".getBytes());id=item.artifactId();
        assertThat(send(client,port,"/api/v1/artifacts",false).statusCode()).isEqualTo(401);
        var bytes=send(client,port,"/api/v1/artifacts/"+id+"/content?projectId="+project+"&sessionId=session",true);
        assertThat(bytes.statusCode()).isEqualTo(200);assertThat(bytes.body()).isEqualTo("safe");
        assertThat(bytes.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;");
        assertThat(send(client,port,"/api/v1/artifacts/"+id+"?projectId="+project+"&sessionId=other",true).statusCode()).isEqualTo(404);
      }
      try(var context=application().run(args(true))) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        assertThat(send(client,port,"/api/v1/artifacts/"+id+"/content?projectId="+project+"&sessionId=session",true).body()).isEqualTo("safe");
        assertThat(send(client,port,"/api/v1/artifacts?projectId="+project+"&sessionId=session&runId=run",true).body()).contains(id);
      }
    }
  }
  private SpringApplication application(){var app=new SpringApplication(Config.class);WebApplication.configure(app,"integration-key");return app;}
  private String[] args(boolean enabled){return new String[]{"--rei.web.port=0","--rei.data-dir="+root,"--rei.artifacts.enabled="+enabled,"--logging.config=classpath:web-test-logback.xml"};}
  private HttpResponse<String> send(HttpClient client,int port,String path,boolean auth)throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5));if(auth)request.header("Authorization","Bearer integration-key");
    return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
  }
}
