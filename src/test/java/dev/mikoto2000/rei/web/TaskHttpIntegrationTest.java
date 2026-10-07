package dev.mikoto2000.rei.web;

import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.application.task.TaskManagerConfiguration;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.checkpoint.*;
import dev.mikoto2000.rei.goal.*;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Tag("integration")
class TaskHttpIntegrationTest {
  @TempDir Path root;
  @Configuration(proxyBeanMethods=false)
  @Import({WebApiIntegrationTest.Config.class,TaskManagerConfiguration.class})
  static class Config {
    @Bean("memoryConsolidationDataSource") javax.sql.DataSource data(@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String path) {
      var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+Path.of(path).resolve("state.db"));return source;
    }
    @Bean PersistentCheckpointRepository checkpoints(javax.sql.DataSource source){return new PersistentCheckpointRepository(source,new CheckpointProperties());}
    @Bean GoalRepository goals(javax.sql.DataSource source,Clock clock){return new GoalRepository(source,clock);}
    @Bean PersistentDependencyRepository dependencies(javax.sql.DataSource source,Clock clock){return new PersistentDependencyRepository(source,clock);}
    @Bean PersistentAgentScheduler schedules(javax.sql.DataSource source,Clock clock){return new PersistentAgentScheduler(source,clock);}
    @Bean GoalLoopService loop(){return mock(GoalLoopService.class);}
    @Bean PersistentCheckpointService checkpointService(){return mock(PersistentCheckpointService.class);}
  }
  @Test void defaultOffAuthenticatedTaskSubmissionAndRestartRestorationDoNotExecuteAgain() throws Exception {
    String task,project,session;
    try(var client=HttpClient.newHttpClient()) {
      try(var context=application().run(args(false))) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        assertThat(send(client,port,"/api/v1/tasks",null,true).statusCode()).isEqualTo(404);
      }
      try(var context=application().run(args(true))) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        project=context.getBean(ProjectRegistry.class).resolve(root).id();
        assertThat(send(client,port,"/api/v1/tasks",null,false).statusCode()).isEqualTo(401);
        var receipt=send(client,port,"/api/v1/tasks","{\"projectId\":\""+project+"\",\"message\":\"local fixture task\"}",true);
        assertThat(receipt.statusCode()).isEqualTo(202);
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(receipt.body());task=json.path("id").asText();session=json.path("sessionId").asText();
        String location=receipt.headers().firstValue("Location").orElseThrow();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(!send(client,port,location,null,true).body().contains("COMPLETED")&&System.nanoTime()<deadline)Thread.sleep(10);
        assertThat(send(client,port,location,null,true).body()).contains("COMPLETED");
        assertThat(send(client,port,"/api/v1/tasks/"+task+"?projectId="+project+"&sessionId=foreign",null,true).statusCode()).isEqualTo(404);
        assertThat(send(client,port,"/api/v1/tasks?projectId="+project+"&sessionId="+session+"&limit=1",null,true).body()).contains(task);
      }
      try(var context=application().run(args(true))) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        var restored=send(client,port,"/api/v1/tasks/"+task+"?projectId="+project+"&sessionId="+session,null,true);
        assertThat(restored.statusCode()).isEqualTo(200);assertThat(restored.body()).contains("COMPLETED","EXCLUSIVE");
        var page=new com.fasterxml.jackson.databind.ObjectMapper().readTree(send(client,port,"/api/v1/tasks",null,true).body());
        assertThat(page.path("items").size()).isEqualTo(1);
      }
    }
  }
  private SpringApplication application(){var app=new SpringApplication(Config.class);WebApplication.configure(app,"integration-key");return app;}
  private String[] args(boolean enabled){return new String[]{"--rei.web.port=0","--rei.data-dir="+root,"--rei.task-manager.enabled="+enabled,"--logging.config=classpath:web-test-logback.xml"};}
  private HttpResponse<String> send(HttpClient client,int port,String path,String body,boolean auth)throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(3));
    if(auth)request.header("Authorization","Bearer integration-key");
    if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
  }
}
